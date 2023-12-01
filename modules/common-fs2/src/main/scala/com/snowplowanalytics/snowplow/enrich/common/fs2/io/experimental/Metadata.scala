/*
 * Copyright (c) 2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.common.fs2.io.experimental

import java.time.Instant
import java.util.UUID

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import cats.implicits._
import cats.Applicative
import cats.data.NonEmptyList
import cats.kernel.Semigroup
import cats.effect.{Async, Clock, ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.effect.concurrent.Ref
import fs2.Stream
import io.circe.Json
import io.circe.parser._
import io.circe.syntax._
import org.http4s.Uri
import org.http4s.client.Client

import com.snowplowanalytics.iglu.core.{SchemaKey, SchemaVer, SelfDescribingData}
import com.snowplowanalytics.iglu.core.circe.implicits._
import com.snowplowanalytics.snowplow.scalatracker.{Emitter, Tracker}
import com.snowplowanalytics.snowplow.scalatracker.emitters.http4s.Http4sEmitter
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.{Metadata => MetadataConfig}
import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent

/**
 * EXPERIMENTAL: This code is subject to change or being removed
 *
 * Aggregate relationships between events and entities
 * observed over a given period of time.
 */
trait Metadata[F[_]] {
  def report: Stream[F, Unit]
  def observe(events: List[EnrichedEvent]): F[Unit]
}

object Metadata {
  type Aggregates = Map[MetadataEvent, EntitiesAndCount]
  case class EntitiesAndCount(entities: Set[SchemaKey], count: Int)

  implicit private def entitiesAndCountSemigroup: Semigroup[EntitiesAndCount] =
    new Semigroup[EntitiesAndCount] {
      override def combine(x: EntitiesAndCount, y: EntitiesAndCount): EntitiesAndCount =
        EntitiesAndCount(
          x.entities |+| y.entities,
          x.count + y.count
        )
    }

  private implicit def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  def build[F[_]: ContextShift: ConcurrentEffect: Timer](
    config: MetadataConfig,
    reporter: MetadataReporter[F]
  ): F[Metadata[F]] =
    MetadataEventsRef.init[F].map { observedRef =>
      new Metadata[F] {
        def report: Stream[F, Unit] =
          for {
            _ <- Stream.eval(Logger[F].info("Starting metadata repoter"))
            _ <- Stream.bracket(ConcurrentEffect[F].unit)(_ => submit(reporter, observedRef))
            _ <- Stream.fixedDelay[F](config.interval)
            _ <- Stream.eval(submit(reporter, observedRef))
          } yield ()

        def observe(events: List[EnrichedEvent]): F[Unit] =
          observedRef.aggregates.update(recalculate(_, events))
      }
    }

  def noop[F[_]: Async]: Metadata[F] =
    new Metadata[F] {
      def report: Stream[F, Unit] = Stream.never[F]
      def observe(events: List[EnrichedEvent]): F[Unit] = Applicative[F].unit
    }

  private def submit[F[_]: Sync: Clock](reporter: MetadataReporter[F], ref: MetadataEventsRef[F]): F[Unit] =
    for {
      snapshot <- MetadataEventsRef.snapshot(ref)
      _ <- snapshot.aggregates.toList.traverse {
             case (event, entitiesAndCount) =>
               reporter.report(snapshot.periodStart, snapshot.periodEnd, event, entitiesAndCount)
           }
    } yield ()

  trait MetadataReporter[F[_]] {
    def report(
      periodStart: Instant,
      periodEnd: Instant,
      event: MetadataEvent,
      entitiesAndCount: EntitiesAndCount
    ): F[Unit]
  }

  case class HttpMetadataReporter[F[_]: ConcurrentEffect: Timer](
    config: MetadataConfig,
    appName: String,
    client: Client[F]
  ) extends MetadataReporter[F] {
    def initTracker(
      config: MetadataConfig,
      appName: String,
      client: Client[F]
    ): Resource[F, Tracker[F]] =
      for {
        emitter <- Http4sEmitter.build(
                     Emitter.EndpointParams(
                       config.endpoint.host.map(_.toString()).getOrElse("localhost"),
                       config.endpoint.port,
                       https = config.endpoint.scheme.map(_ == Uri.Scheme.https).getOrElse(false)
                     ),
                     client,
                     retryPolicy = Emitter.RetryPolicy.MaxAttempts(10),
                     callback = Some(emitterCallback _)
                   )
      } yield new Tracker(NonEmptyList.of(emitter), "tracker-metadata", appName)

    def report(
      periodStart: Instant,
      periodEnd: Instant,
      event: MetadataEvent,
      entitiesAndCount: EntitiesAndCount
    ): F[Unit] =
      initTracker(config, appName, client).use { t =>
        Logger[F].info(s"Tracking observed event ${event.schema.toSchemaUri}") >>
          t.trackSelfDescribingEvent(
            mkWebhookEvent(config.organizationId, config.pipelineId, periodStart, periodEnd, event, entitiesAndCount.count),
            mkWebhookContexts(entitiesAndCount.entities).toSeq
          ) >> t.flushEmitters()
      }

    private def emitterCallback(
      params: Emitter.EndpointParams,
      req: Emitter.Request,
      res: Emitter.Result
    ): F[Unit] =
      res match {
        case Emitter.Result.Success(_) =>
          Logger[F].info(s"Metadata successfully sent to ${params.getGetUri}")
        case Emitter.Result.Failure(code) =>
          Logger[F].warn(s"Sending metadata got unexpected HTTP code $code from ${params.getUri}")
        case Emitter.Result.TrackerFailure(exception) =>
          Logger[F].warn(
            s"Metadata failed to reach ${params.getUri} with following exception $exception after ${req.attempt} attempts"
          )
        case Emitter.Result.RetriesExceeded(failure) =>
          Logger[F].error(s"Stopped trying to send metadata after following failure: $failure")
      }
  }

  /**
   * A metadata domain representation of an enriched event
   *
   * @param schema - schema key of given event
   * @param source - `app_id` for given event
   * @param tracker - `v_tracker` for given event
   * @param platform - The platform the app runs on for given event (`platform` field)
   */
  case class MetadataEvent(
    schema: SchemaKey,
    source: Option[String],
    tracker: Option[String],
    platform: Option[String]
  )
  object MetadataEvent {
    def apply(event: EnrichedEvent): MetadataEvent =
      MetadataEvent(
        SchemaKey(
          Option(event.event_vendor).getOrElse("unknown-vendor"),
          Option(event.event_name).getOrElse("unknown-name"),
          Option(event.event_format).getOrElse("unknown-format"),
          Option(event.event_version).toRight("unknown-version").flatMap(SchemaVer.parseFull).getOrElse(SchemaVer.Full(0, 0, 0))
        ),
        Option(event.app_id),
        Option(event.v_tracker),
        Option(event.platform)
      )
  }

  /**
   * An representation of observed metadata events and entites attached to them over a period of time
   *
   * @param aggregates - mappings of entities observed (since `periodStart`) for given `MetadataEvent`s
   * @param periodStart - since when `aggregates` are accumulated
   * @param periodEnd - until when `aggregates` are accumulated
   */
  case class MetadataSnapshot(
    aggregates: Aggregates,
    periodStart: Instant,
    periodEnd: Instant
  )

  /**
   * Internal state representation for current metadata period
   * @param aggregates - mappings of entities observed (since `periodStart`) for given `MetadataEvent`s
   * @param periodStart - since when `aggregates` are accumulated
   */
  case class MetadataEventsRef[F[_]](
    aggregates: Ref[F, Aggregates],
    periodStart: Ref[F, Instant]
  )

  object MetadataEventsRef {
    def init[F[_]: Sync: Clock]: F[MetadataEventsRef[F]] =
      for {
        time <- Clock[F].instantNow
        aggregates <- Ref.of[F, Aggregates](Map.empty)
        periodStart <- Ref.of[F, Instant](time)
      } yield MetadataEventsRef(aggregates, periodStart)
    def snapshot[F[_]: Sync: Clock](ref: MetadataEventsRef[F]): F[MetadataSnapshot] =
      for {
        periodEnd <- Clock[F].instantNow
        aggregates <- ref.aggregates.getAndSet(Map.empty)
        periodStart <- ref.periodStart.getAndSet(periodEnd)
      } yield MetadataSnapshot(aggregates, periodStart, periodEnd)
  }

  def unwrapEntities(event: EnrichedEvent): Set[SchemaKey] = {
    def unwrap(str: String) =
      decode[SelfDescribingData[Json]](str)
        .traverse(
          _.data
            .as[List[SelfDescribingData[Json]]]
            .traverse(_.map(_.schema))
            .flatMap(_.toList)
        )
        .flatMap(_.toList)
        .toSet

    unwrap(event.contexts) ++ unwrap(event.derived_contexts)
  }

  def schema(event: EnrichedEvent): SchemaKey =
    SchemaKey(
      Option(event.event_vendor).getOrElse("unknown-vendor"),
      Option(event.event_name).getOrElse("unknown-name"),
      Option(event.event_format).getOrElse("unknown-format"),
      SchemaVer.parseFull(event.event_version).getOrElse(SchemaVer.Full(0, 0, 0))
    )

  def recalculate(previous: Aggregates, events: List[EnrichedEvent]): Aggregates =
    previous |+| events.map(e => Map(MetadataEvent(e) -> EntitiesAndCount(unwrapEntities(e), 1))).combineAll

  def mkWebhookEvent(
    organizationId: UUID,
    pipelineId: UUID,
    periodStart: Instant,
    periodEnd: Instant,
    event: MetadataEvent,
    count: Int
  ): SelfDescribingData[Json] =
    SelfDescribingData(
      SchemaKey("com.snowplowanalytics.console", "observed_event", "jsonschema", SchemaVer.Full(6, 0, 0)),
      Json.obj(
        "organizationId" -> organizationId.asJson,
        "pipelineId" -> pipelineId.asJson,
        "eventVendor" -> event.schema.vendor.asJson,
        "eventName" -> event.schema.name.asJson,
        "eventVersion" -> event.schema.version.asString.asJson,
        "source" -> event.source.getOrElse("unknown-source").asJson,
        "tracker" -> event.tracker.getOrElse("unknown-tracker").asJson,
        "platform" -> event.platform.getOrElse("unknown-platform").asJson,
        "eventVolume" -> Json.fromInt(count),
        "periodStart" -> periodStart.asJson,
        "periodEnd" -> periodEnd.asJson
      )
    )

  def mkWebhookContexts(entities: Set[SchemaKey]): Set[SelfDescribingData[Json]] =
    entities.map(entity =>
      SelfDescribingData[Json](
        SchemaKey("com.snowplowanalytics.console", "observed_entity", "jsonschema", SchemaVer.Full(4, 0, 0)),
        Json.obj("entityVendor" -> entity.vendor.asJson,
                 "entityName" -> entity.name.asJson,
                 "entityVersion" -> entity.version.asString.asJson
        )
      )
    )
}
