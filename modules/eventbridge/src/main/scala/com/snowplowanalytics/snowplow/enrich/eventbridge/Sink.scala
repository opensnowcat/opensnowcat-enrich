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
package com.snowplowanalytics.snowplow.enrich.eventbridge

import cats.effect.{Resource, Sync}
import cats.effect.kernel.{Async, Ref}
import cats.implicits._
import cats.{Monoid, Parallel}
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Output
import com.snowplowanalytics.snowplow.enrich.common.fs2.io.Retries
import com.snowplowanalytics.snowplow.enrich.common.fs2.{AttributedByteSink, AttributedData, ByteSink}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicy
import retry.syntax.all._
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import software.amazon.awssdk.services.eventbridge.model.{EventBridgeException, PutEventsRequest, PutEventsRequestEntry, PutEventsResponse}

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.jdk.CollectionConverters.{collectionAsScalaIterableConverter, seqAsJavaListConverter}

object Sink {

  private implicit def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  def init[F[_]: Async: Parallel](
    output: Output
  ): Resource[F, ByteSink[F]] =
    for {
      sink <- initAttributed(output)
    } yield (records: List[Array[Byte]]) => sink(records.map(AttributedData(_, UUID.randomUUID().toString, Map.empty)))

  def initAttributed[F[_]: Async: Parallel](
    output: Output
  ): Resource[F, AttributedByteSink[F]] =
    output match {
      case o: Output.Eventbridge =>
        o.region.orElse(getRuntimeRegion) match {
          case Some(region) =>
            for {
              producer <- Resource.eval[F, EventBridgeClient](mkProducer(o, region))
            } yield (records: List[AttributedData[Array[Byte]]]) => writeToEventbridge(o, producer, toEventBridgeEvents(records, o))
          case None =>
            Resource.eval(Sync[F].raiseError(new RuntimeException(s"Region not found in the config and in the runtime")))
        }
      case o =>
        Resource.eval(Sync[F].raiseError(new IllegalArgumentException(s"Output $o is not Eventbridge")))
    }

  private def mkProducer[F[_]: Sync](
    config: Output.Eventbridge,
    region: String
  ): F[EventBridgeClient] =
    Sync[F].delay {
      val builder =
        EventBridgeClient
          .builder()
          .region(Region.of(region))

      config.customEndpoint
        .map(builder.endpointOverride)
        .getOrElse(builder)
        .build
    }

  private def toEventBridgeEvents(
    events: List[AttributedData[Array[Byte]]],
    output: Output.Eventbridge
  ): List[PutEventsRequestEntry] =
    events
      .map { event =>
        val data = new String(event.data)
        PutEventsRequestEntry
          .builder()
          .eventBusName(output.eventBusName)
          .source(output.eventBusSource)
          .detail(data)
          .detailType("enrich-event")
          .build()
      }

  private def writeToEventbridge[F[_]: Async: Parallel](
    config: Output.Eventbridge,
    eventbridge: EventBridgeClient,
    allEvents: List[PutEventsRequestEntry]
  ): F[Unit] = {
    // The maximum size for a PutEvents event entry is 256 KB. Entry size is calculated including the event and any necessary characters and keys of the JSON representation of the event
    // See: https://docs.aws.amazon.com/eventbridge/latest/APIReference/API_PutEvents.html
    val recordByteLimit = 256 * 1024
    val (events, invalidEvents) = allEvents.partition(r => getRecordSize(r) <= recordByteLimit)
    val policyForErrors = Retries.fullJitter[F](config.backoffPolicy)
    val policyForThrottling = Retries.fibonacci[F](config.throttledBackoffPolicy)

    def runAndCaptureFailures(ref: Ref[F, List[PutEventsRequestEntry]]): F[List[PutEventsRequestEntry]] =
      for {
        records <- ref.get
        failures <- group(records, recordLimit = config.recordLimit, sizeLimit = config.byteLimit, getRecordSize)
                      .parTraverse(g => tryWriteToEventbridge(config, eventbridge, g, policyForErrors))
        flattened = failures.flatten
        _ <- ref.set(flattened)
      } yield flattened

    for {
      ref <- Ref.of(events)
      _ <- Sync[F].whenA(invalidEvents.nonEmpty) {
             invalidEvents.map { e =>
               val dataSize = getRecordSize(e)
               Logger[F].warn(s"Event data size ($dataSize bytes) exceeds 256 KB limit. Skipping event")
             }.sequence_
           }
      failures <- runAndCaptureFailures(ref)
                    .retryingOnFailures(
                      policy = policyForThrottling,
                      wasSuccessful = l => Sync[F].pure(l.isEmpty),
                      onFailure = { case (result, retryDetails) =>
                        val msg = failureMessageForThrottling(result, config.eventBusName)
                        Logger[F].warn(s"$msg (${retryDetails.retriesSoFar} retries from cats-retry)")
                      }
                    )
      _ <- if (failures.isEmpty) Sync[F].unit
           else Sync[F].raiseError(new RuntimeException(failureMessageForThrottling(failures, config.eventBusName)))
    } yield ()
  }

  /**
   * This function takes a list of records and splits it into several lists,
   * where each list is as big as possible with respecting the record limit and the size limit
   */
  private[eventbridge] def group[A](
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
      .foldLeft(List.empty[Batch]) { case (acc, record) =>
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

  /**
   * Try writing a batch, and returns a list of the failures to be retried:
   *
   * If we are not throttled by eventbridge, then the list is empty.
   * If we are throttled by eventbridge, the list contains throttled records and records that gave internal errors.
   * If there is an exception, or if all records give internal errors, then we retry using the policy.
   */
  private def tryWriteToEventbridge[F[_]: Async](
    config: Output.Eventbridge,
    eventbridge: EventBridgeClient,
    events: List[PutEventsRequestEntry],
    retryPolicy: RetryPolicy[F]
  ): F[Vector[PutEventsRequestEntry]] =
    Logger[F].debug(s"Writing ${events.size} records to ${config.eventBusName}") *>
      Async[F]
        .blocking(putEvents(eventbridge, events))
        .map(TryBatchResult.build(events, _))
        .retryingOnFailuresAndSomeErrors(
          policy = retryPolicy,
          wasSuccessful = r => Sync[F].pure(!r.shouldRetrySameBatch),
          onFailure = { case (result, retryDetails) =>
            val msg = failureMessageForInternalErrors(events, config.eventBusName, result)
            Logger[F].error(s"$msg (${retryDetails.retriesSoFar} retries from cats-retry)")
          },
          onError = (exception, retryDetails) =>
            Logger[F]
              .error(exception)(
                s"Writing ${events.size} records to ${config.eventBusName} errored (${retryDetails.retriesSoFar} retries from cats-retry)"
              ),
          isWorthRetrying = {
            // Do not retry when getting error 4xx, these occur due to a request problem and retrying won't help
            // For example:
            // - Total size of the entries in the request is over the limit
            case ex: EventBridgeException if ex.statusCode() % 100 == 4 => Sync[F].pure(false)
            case _ => Sync[F].pure(true)
          }
        )
        .flatMap { result =>
          if (result.shouldRetrySameBatch)
            Sync[F].raiseError(new RuntimeException(failureMessageForInternalErrors(events, config.eventBusName, result)))
          else
            result.nextBatchAttempt.pure[F]
        }

  /**
   * The result of trying to write a batch to eventbridge
   *
   * @param nextBatchAttempt     Records to re-package into another batch, either because of throttling or an internal error
   * @param hadSuccess           Whether one or more records in the batch were written successfully
   * @param wasThrottled         Whether at least one of retries is because of throttling
   * @param exampleInternalError A message to help with logging
   */
  private case class TryBatchResult(
    nextBatchAttempt: Vector[PutEventsRequestEntry],
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

    def build(records: List[PutEventsRequestEntry], prr: PutEventsResponse): TryBatchResult =
      if (prr.failedEntryCount().toInt =!= 0)
        records
          .zip(prr.entries().asScala)
          .foldMap { case (orig, recordResult) =>
            Option(recordResult.errorCode()) match {
              case None =>
                TryBatchResult(Vector.empty, true, false, None)
              case Some("ThrottlingException") =>
                TryBatchResult(Vector(orig), false, true, None)
              case Some(_) =>
                TryBatchResult(Vector(orig), false, false, Option(recordResult.errorMessage()))
            }
          }
      else
        TryBatchResult(Vector.empty, true, false, None)
  }

  private def putEvents(
    eventbridge: EventBridgeClient,
    events: List[PutEventsRequestEntry]
  ): PutEventsResponse = {
    val request = PutEventsRequest
      .builder()
      .entries(events.asJava)
      .build()

    eventbridge.putEvents(request)
  }

  /**
   * Official AWS code adapted to Scala to compute the entry size
   *
   * See https://docs.aws.amazon.com/eventbridge/latest/userguide/eb-putevent-size.html
   */
  private def getRecordSize(entry: PutEventsRequestEntry): Int = {
    var size = 0
    if (entry.time() != null) size += 14
    size += entry.source().getBytes(StandardCharsets.UTF_8).length
    size += entry.detailType().getBytes(StandardCharsets.UTF_8).length
    if (entry.detail() != null) size += entry.detail().getBytes(StandardCharsets.UTF_8).length
    if (entry.resources() != null) {
      import scala.collection.JavaConverters._
      for (resource <- entry.resources().asScala)
        if (resource != null) size += resource.getBytes(StandardCharsets.UTF_8).length
    }
    size
  }

  private def failureMessageForInternalErrors(
    records: List[PutEventsRequestEntry],
    streamName: String,
    result: TryBatchResult
  ): String = {
    val exampleMessage = result.exampleInternalError.getOrElse("none")
    s"Writing ${records.size} records to $streamName errored with internal failures. Example error message [$exampleMessage]"
  }

  private def failureMessageForThrottling(
    records: List[PutEventsRequestEntry],
    streamName: String
  ): String =
    s"Exceeded Eventbridge provisioned throughput: ${records.size} records failed writing to $streamName."
}
