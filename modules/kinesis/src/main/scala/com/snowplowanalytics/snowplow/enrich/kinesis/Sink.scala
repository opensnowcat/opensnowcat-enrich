/*
 * Copyright (c) 2022-2022 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.enrich.kinesis

import java.nio.ByteBuffer
import java.util.UUID

import scala.collection.JavaConverters._

import cats.implicits._
import cats.{Monoid, Parallel}

import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync, Timer}
import cats.effect.concurrent.Ref

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import retry.syntax.all._
import retry.RetryPolicy

import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration

import com.amazonaws.services.kinesis.model._
import com.amazonaws.services.kinesis.{AmazonKinesis, AmazonKinesisClientBuilder}

import com.snowplowanalytics.snowplow.enrich.common.fs2.{AttributedByteSink, AttributedData, ByteSink}
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Output
import com.snowplowanalytics.snowplow.enrich.common.fs2.io.Retries

object Sink {

  private implicit def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  def init[F[_]: Concurrent: ContextShift: Parallel: Timer](
    blocker: Blocker,
    output: Output
  ): Resource[F, ByteSink[F]] =
    for {
      sink <- initAttributed(blocker, output)
    } yield (records: List[Array[Byte]]) => sink(records.map(AttributedData(_, UUID.randomUUID().toString, Map.empty)))

  def initAttributed[F[_]: Concurrent: ContextShift: Parallel: Timer](
    blocker: Blocker,
    output: Output
  ): Resource[F, AttributedByteSink[F]] =
    output match {
      case o: Output.Kinesis =>
        o.region.orElse(getRuntimeRegion) match {
          case Some(region) =>
            for {
              producer <- Resource.eval[F, AmazonKinesis](mkProducer(o, region))
            } yield records => writeToKinesis(blocker, o, producer, toKinesisRecords(records))
          case None =>
            Resource.eval(Sync[F].raiseError(new RuntimeException(s"Region not found in the config and in the runtime")))
        }
      case o =>
        Resource.eval(Sync[F].raiseError(new IllegalArgumentException(s"Output $o is not Kinesis")))
    }

  private def mkProducer[F[_]: Sync](
    config: Output.Kinesis,
    region: String
  ): F[AmazonKinesis] =
    for {
      builder <- Sync[F].delay(AmazonKinesisClientBuilder.standard)
      withEndpoint <- config.customEndpoint match {
                        case Some(endpoint) =>
                          Sync[F].delay(builder.withEndpointConfiguration(new EndpointConfiguration(endpoint.toString, region)))
                        case None =>
                          Sync[F].delay(builder.withRegion(region))
                      }
      kinesis <- Sync[F].delay(withEndpoint.build())
      _ <- streamExists(kinesis, config.streamName)
    } yield kinesis

  private def streamExists[F[_]: Sync](kinesis: AmazonKinesis, stream: String): F[Unit] =
    for {
      described <- Sync[F].delay(kinesis.describeStream(stream))
      status = described.getStreamDescription.getStreamStatus
      exists <- status match {
                  case "ACTIVE" | "UPDATING" =>
                    Sync[F].unit
                  case _ =>
                    Sync[F].raiseError[Unit](new IllegalArgumentException(s"Stream $stream doesn't exist or can't be accessed"))
                }
    } yield exists

  private def writeToKinesis[F[_]: ContextShift: Parallel: Sync: Timer](
    blocker: Blocker,
    config: Output.Kinesis,
    kinesis: AmazonKinesis,
    records: List[PutRecordsRequestEntry]
  ): F[Unit] = {
    val policyForErrors = Retries.fullJitter[F](config.backoffPolicy)
    val policyForThrottling = Retries.fibonacci[F](config.throttledBackoffPolicy)

    def runAndCaptureFailures(ref: Ref[F, List[PutRecordsRequestEntry]]): F[List[PutRecordsRequestEntry]] =
      for {
        records <- ref.get
        failures <- group(records, config.recordLimit, config.byteLimit, getRecordSize)
                      .parTraverse(g => tryWriteToKinesis(blocker, config, kinesis, g, policyForErrors))
        flattened = failures.flatten
        _ <- ref.set(flattened)
      } yield flattened

    for {
      ref <- Ref.of(records)
      failures <- runAndCaptureFailures(ref)
                    .retryingOnFailures(
                      policy = policyForThrottling,
                      wasSuccessful = _.isEmpty,
                      onFailure = {
                        case (result, retryDetails) =>
                          val msg = failureMessageForThrottling(result, config.streamName)
                          Logger[F].warn(s"$msg (${retryDetails.retriesSoFar} retries from cats-retry)")
                      }
                    )
      _ <- if (failures.isEmpty) Sync[F].unit
           else Sync[F].raiseError(new RuntimeException(failureMessageForThrottling(failures, config.streamName)))
    } yield ()
  }

  /**
   *  This function takes a list of records and splits it into several lists,
   *  where each list is as big as possible with respecting the record limit and
   *  the size limit.
   */
  private[kinesis] def group[A](
    records: List[A],
    recordLimit: Int,
    sizeLimit: Int,
    getRecordSize: A => Int
  ): List[List[A]] = {
    case class Batch(
      size: Int,
      count: Int,
      records: List[A]
    )

    records
      .foldLeft(List.empty[Batch]) {
        case (acc, record) =>
          val recordSize = getRecordSize(record)
          acc match {
            case head :: tail =>
              if (head.count + 1 > recordLimit || head.size + recordSize > sizeLimit)
                List(Batch(recordSize, 1, List(record))) ++ List(head) ++ tail
              else
                List(Batch(head.size + recordSize, head.count + 1, record :: head.records)) ++ tail
            case Nil =>
              List(Batch(recordSize, 1, List(record)))
          }
      }
      .map(_.records)
  }

  private def getRecordSize(record: PutRecordsRequestEntry) =
    record.getData.array.size + record.getPartitionKey.getBytes.size

  /**
   * Try writing a batch, and returns a list of the failures to be retried:
   *
   *  If we are not throttled by kinesis, then the list is empty.
   *  If we are throttled by kinesis, the list contains throttled records and records that gave internal errors.
   *  If there is an exception, or if all records give internal errors, then we retry using the policy.
   */
  private def tryWriteToKinesis[F[_]: ContextShift: Sync: Timer](
    blocker: Blocker,
    config: Output.Kinesis,
    kinesis: AmazonKinesis,
    records: List[PutRecordsRequestEntry],
    retryPolicy: RetryPolicy[F]
  ): F[Vector[PutRecordsRequestEntry]] =
    Logger[F].debug(s"Writing ${records.size} records to ${config.streamName}") *>
      blocker
        .blockOn(Sync[F].delay(putRecords(kinesis, config.streamName, records)))
        .map(TryBatchResult.build(records, _))
        .retryingOnFailuresAndAllErrors(
          policy = retryPolicy,
          wasSuccessful = r => !r.shouldRetrySameBatch,
          onFailure = {
            case (result, retryDetails) =>
              val msg = failureMessageForInternalErrors(records, config.streamName, result)
              Logger[F].error(s"$msg (${retryDetails.retriesSoFar} retries from cats-retry)")
          },
          onError = (exception, retryDetails) =>
            Logger[F]
              .error(exception)(
                s"Writing ${records.size} records to ${config.streamName} errored (${retryDetails.retriesSoFar} retries from cats-retry)"
              )
        )
        .flatMap { result =>
          if (result.shouldRetrySameBatch)
            Sync[F].raiseError(new RuntimeException(failureMessageForInternalErrors(records, config.streamName, result)))
          else
            result.nextBatchAttempt.pure[F]
        }

  private def toKinesisRecords(records: List[AttributedData[Array[Byte]]]): List[PutRecordsRequestEntry] =
    records.map { r =>
      val data = ByteBuffer.wrap(r.data)
      val prre = new PutRecordsRequestEntry()
      prre.setPartitionKey(r.partitionKey)
      prre.setData(data)
      prre
    }

  /**
   * The result of trying to write a batch to kinesis
   *  @param nextBatchAttempt Records to re-package into another batch, either because of throttling or an internal error
   *  @param hadSuccess Whether one or more records in the batch were written successfully
   *  @param wasThrottled Whether at least one of retries is because of throttling
   *  @param exampleInternalError A message to help with logging
   */
  private case class TryBatchResult(
    nextBatchAttempt: Vector[PutRecordsRequestEntry],
    hadSuccess: Boolean,
    wasThrottled: Boolean,
    exampleInternalError: Option[String]
  ) {
    // Only retry the exact same again if no record was successfully inserted, and all the errors
    // were not throughput exceeded exceptions
    def shouldRetrySameBatch: Boolean =
      !hadSuccess && !wasThrottled
  }

  private object TryBatchResult {

    implicit private def tryBatchResultMonoid: Monoid[TryBatchResult] =
      new Monoid[TryBatchResult] {
        override val empty: TryBatchResult = TryBatchResult(Vector.empty, false, false, None)
        override def combine(x: TryBatchResult, y: TryBatchResult): TryBatchResult =
          TryBatchResult(
            x.nextBatchAttempt ++ y.nextBatchAttempt,
            x.hadSuccess || y.hadSuccess,
            x.wasThrottled || y.wasThrottled,
            x.exampleInternalError.orElse(y.exampleInternalError)
          )
      }

    def build(records: List[PutRecordsRequestEntry], prr: PutRecordsResult): TryBatchResult =
      if (prr.getFailedRecordCount.toInt =!= 0)
        records
          .zip(prr.getRecords.asScala)
          .foldMap {
            case (orig, recordResult) =>
              Option(recordResult.getErrorCode) match {
                case None =>
                  TryBatchResult(Vector.empty, true, false, None)
                case Some("ProvisionedThroughputExceededException") =>
                  TryBatchResult(Vector(orig), false, true, None)
                case Some(_) =>
                  TryBatchResult(Vector(orig), false, false, Option(recordResult.getErrorMessage))
              }
          }
      else
        TryBatchResult(Vector.empty, true, false, None)
  }

  private def putRecords(
    kinesis: AmazonKinesis,
    streamName: String,
    records: List[PutRecordsRequestEntry]
  ): PutRecordsResult = {
    val putRecordsRequest = {
      val prr = new PutRecordsRequest()
      prr.setStreamName(streamName)
      prr.setRecords(records.asJava)
      prr
    }
    kinesis.putRecords(putRecordsRequest)
  }

  private def failureMessageForInternalErrors(
    records: List[PutRecordsRequestEntry],
    streamName: String,
    result: TryBatchResult
  ): String = {
    val exampleMessage = result.exampleInternalError.getOrElse("none")
    s"Writing ${records.size} records to $streamName errored with internal failures. Example error message [$exampleMessage]"
  }

  private def failureMessageForThrottling(
    records: List[PutRecordsRequestEntry],
    streamName: String
  ): String =
    s"Exceeded Kinesis provisioned throughput: ${records.size} records failed writing to $streamName."

}
