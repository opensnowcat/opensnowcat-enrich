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

import cats.data.Validated
import cats.effect.concurrent.Ref
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync, Timer}
import cats.implicits._
import cats.{Monoid, Parallel}
import com.snowplowanalytics.snowplow.analytics.scalasdk.Event
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Output
import com.snowplowanalytics.snowplow.enrich.common.fs2.io.Retries
import com.snowplowanalytics.snowplow.enrich.common.fs2.{AttributedByteSink, AttributedData, ByteSink}
import io.circe.Json
import io.circe.syntax._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicy
import retry.implicits.{retrySyntaxBase, retrySyntaxError}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.eventbridge.EventBridgeClient
import software.amazon.awssdk.services.eventbridge.model.{PutEventsRequest, PutEventsRequestEntry, PutEventsResponse}

import java.nio.charset.StandardCharsets
import java.util.{Base64, UUID}
import scala.jdk.CollectionConverters.{collectionAsScalaIterableConverter, seqAsJavaListConverter}

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
      case o: Output.Eventbridge =>
        o.region.orElse(getRuntimeRegion) match {
          case Some(region) =>
            for {
              producer <- Resource.eval[F, EventBridgeClient](mkProducer(o, region))
            } yield (records: List[AttributedData[Array[Byte]]]) =>
              writeToEventbridge(blocker, o, producer, toEventBridgeEvents(records, o))
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

  def toEventBridgeEvents(
    events: List[AttributedData[Array[Byte]]],
    output: Output.Eventbridge
  ): List[PutEventsRequestEntry] =
    events
      .map { event =>
        val tsv = new String(event.data)
        lazy val base64TSV = Base64.getEncoder.encodeToString(tsv.getBytes(StandardCharsets.UTF_8))

        val json = Event.parse(tsv) match {
          case Validated.Valid(event) =>
            val eventJson = event.toJson(false)

            lazy val host = for {
              headers <- eventJson.hcursor
                           .downField("contexts_org_ietf_http_header_1")
                           .as[List[Json]]
                           .toOption

              hostHeader <- headers.find { header =>
                              header.hcursor
                                .downField("name")
                                .as[String]
                                .toOption
                                .contains("Host")
                            }

              host <- hostHeader.hcursor
                        .downField("value")
                        .as[String]
                        .toOption
            } yield host
            val attachments = List((output.payload.contains(true), "payload", () => base64TSV.asJson),
                                   (output.collector.contains(true), "collector", () => host.asJson)
            )

            attachments.foldLeft(eventJson) {
              case (current, (include, key, getValue)) =>
                if (include) current.deepMerge(Map(key -> getValue()).asJson)
                else current
            }
          case Validated.Invalid(error) =>
            // when the event content is not a TSV, there is a chance that we are processing a bad row, in such a case
            // we are interested in sending the raw event if it is a json, otherwise, we return a generic error.
            io.circe.parser.parse(tsv).getOrElse {
              Map("error" -> error.toString, "tsv" -> base64TSV).asJson
            }
        }

        PutEventsRequestEntry
          .builder()
          .eventBusName(output.eventBusName)
          .source(output.eventBusSource)
          .detail(json.noSpaces)
          .detailType("enrich-event")
          .build()
      }

  private def writeToEventbridge[F[_]: ContextShift: Parallel: Sync: Timer](
    blocker: Blocker,
    config: Output.Eventbridge,
    eventbridge: EventBridgeClient,
    events: List[PutEventsRequestEntry]
  ): F[Unit] = {
    val policyForErrors = Retries.fullJitter[F](config.backoffPolicy)
    val policyForThrottling = Retries.fibonacci[F](config.throttledBackoffPolicy)

    def runAndCaptureFailures(ref: Ref[F, List[PutEventsRequestEntry]]): F[List[PutEventsRequestEntry]] =
      for {
        records <- ref.get
        failures <- group(records, recordLimit = config.recordLimit, sizeLimit = config.byteLimit, getRecordSize)
                      .parTraverse(g => tryWriteToEventbridge(blocker, config, eventbridge, g, policyForErrors))
        flattened = failures.flatten
        _ <- ref.set(flattened)
      } yield flattened

    for {
      ref <- Ref.of(events)
      failures <- runAndCaptureFailures(ref)
                    .retryingOnFailures(
                      policy = policyForThrottling,
                      wasSuccessful = _.isEmpty,
                      onFailure = {
                        case (result, retryDetails) =>
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

  /**
   * Try writing a batch, and returns a list of the failures to be retried:
   *
   * If we are not throttled by eventbridge, then the list is empty.
   * If we are throttled by eventbridge, the list contains throttled records and records that gave internal errors.
   * If there is an exception, or if all records give internal errors, then we retry using the policy.
   */
  private def tryWriteToEventbridge[F[_]: ContextShift: Sync: Timer](
    blocker: Blocker,
    config: Output.Eventbridge,
    eventbridge: EventBridgeClient,
    events: List[PutEventsRequestEntry],
    retryPolicy: RetryPolicy[F]
  ): F[Vector[PutEventsRequestEntry]] =
    Logger[F].debug(s"Writing ${events.size} records to ${config.eventBusName}") *>
      blocker
        .blockOn(Sync[F].delay(putEvents(eventbridge, events)))
        .map(TryBatchResult.build(events, _))
        .retryingOnFailuresAndAllErrors(
          policy = retryPolicy,
          wasSuccessful = r => !r.shouldRetrySameBatch,
          onFailure = {
            case (result, retryDetails) =>
              val msg = failureMessageForInternalErrors(events, config.eventBusName, result)
              Logger[F].error(s"$msg (${retryDetails.retriesSoFar} retries from cats-retry)")
          },
          onError = (exception, retryDetails) =>
            Logger[F]
              .error(exception)(
                s"Writing ${events.size} records to ${config.eventBusName} errored (${retryDetails.retriesSoFar} retries from cats-retry)"
              )
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
          .foldMap {
            case (orig, recordResult) =>
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
