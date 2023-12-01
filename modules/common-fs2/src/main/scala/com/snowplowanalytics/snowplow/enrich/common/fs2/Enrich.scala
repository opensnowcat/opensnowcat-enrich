/*
 * Copyright (c) 2020-2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.common.fs2

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

import scala.concurrent.duration._

import org.joda.time.DateTime

import cats.data.{NonEmptyList, ValidatedNel}
import cats.{Monad, Parallel}
import cats.implicits._

import cats.effect.{Clock, Concurrent, ContextShift, ExitCase, Fiber, Sync, Timer}
import cats.effect.implicits._

import fs2.concurrent.{NoneTerminatedQueue, Queue}
import fs2.{Pipe, Stream}

import _root_.io.sentry.SentryClient

import _root_.io.circe.syntax._

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import com.snowplowanalytics.iglu.client.IgluCirceClient
import com.snowplowanalytics.iglu.client.resolver.registries.RegistryLookup
import com.snowplowanalytics.snowplow.badrows.{BadRow, Failure, Processor, Payload => BadRowPayload}
import com.snowplowanalytics.snowplow.enrich.common.EtlPipeline
import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import com.snowplowanalytics.snowplow.enrich.common.adapters.AdapterRegistry
import com.snowplowanalytics.snowplow.enrich.common.loaders.{CollectorPayload, ThriftLoader}
import com.snowplowanalytics.snowplow.enrich.common.enrichments.EnrichmentRegistry
import com.snowplowanalytics.snowplow.enrich.common.utils.{ConversionUtils, HttpClient}
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.FeatureFlags

object Enrich {

  private implicit def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  /**
   * Run a primary enrichment stream, reading from [[Environment]] source, enriching
   * via [[enrichWith]] and sinking into the Good, Bad, and Pii sinks.
   *
   * The stream won't download any enrichment DBs, it is responsibility of [[Assets]]
   * [[Assets.State.make]] downloads assets for the first time unconditionally during
   * [[Environment]] initialisation, then if `assetsUpdatePeriod` has been specified -
   * they'll be refreshed periodically by [[Assets.updateStream]]
   */
  def run[F[_]: Concurrent: ContextShift: Clock: HttpClient: Parallel: Timer, A](env: Environment[F, A]): Stream[F, Unit] = {
    val enrichmentsRegistry: F[EnrichmentRegistry[F]] = env.enrichments.get.map(_.registry)
    val enrich: Enrich[F] = {
      implicit val rl: RegistryLookup[F] = env.registryLookup
      enrichWith[F](enrichmentsRegistry,
                    env.adapterRegistry,
                    env.igluClient,
                    env.sentry,
                    env.processor,
                    env.featureFlags,
                    env.metrics.invalidCount
      )
    }

    val enriched =
      env.source.chunks
        .evalTap(chunk => Logger[F].debug(s"Starting to process chunk of size ${chunk.size}"))
        .evalTap(chunk => env.metrics.rawCount(chunk.size))
        .map(chunk => chunk.map(a => (a, env.getPayload(a))))
        .evalMap(chunk =>
          for {
            begin <- Clock[F].realTime(TimeUnit.MILLISECONDS)
            result <-
              env.semaphore.withPermit(
                chunk.toList.map { case (orig, bytes) => enrich(bytes).map((orig, _)) }.parSequenceN(env.streamsSettings.concurrency.enrich)
              )
            end <- Clock[F].realTime(TimeUnit.MILLISECONDS)
            _ <- Logger[F].debug(s"Chunk of size ${chunk.size} enriched in ${end - begin} ms")
          } yield result
        )

    val sinkAndCheckpoint: Pipe[F, List[(A, Result)], Unit] =
      _.parEvalMap(env.streamsSettings.concurrency.sink)(chunk =>
        for {
          begin <- Clock[F].realTime(TimeUnit.MILLISECONDS)
          result <- sinkChunk(chunk.map(_._2), env).as(chunk.map(_._1))
          end <- Clock[F].realTime(TimeUnit.MILLISECONDS)
          _ <- Logger[F].debug(s"Chunk of size ${chunk.size} sunk in ${end - begin} ms")
        } yield result
      )
        .evalMap(env.checkpoint)

    Stream.eval(runWithShutdown(enriched, sinkAndCheckpoint))
  }

  /**
   * Enrich a single `CollectorPayload` to get list of bad rows and/or enriched events
   * @return enriched event or bad row, along with the collector timestamp
   */
  def enrichWith[F[_]: Clock: ContextShift: RegistryLookup: Sync: HttpClient](
    enrichRegistry: F[EnrichmentRegistry[F]],
    adapterRegistry: AdapterRegistry,
    igluClient: IgluCirceClient[F],
    sentry: Option[SentryClient],
    processor: Processor,
    featureFlags: FeatureFlags,
    invalidCount: F[Unit]
  )(
    row: Array[Byte]
  ): F[Result] = {
    val payload = ThriftLoader.toCollectorPayload(row, processor, featureFlags.tryBase64Decoding)
    val collectorTstamp = payload.toOption.flatMap(_.flatMap(_.context.timestamp).map(_.getMillis))

    val result =
      for {
        etlTstamp <- Clock[F].realTime(TimeUnit.MILLISECONDS).map(millis => new DateTime(millis))
        registry <- enrichRegistry
        enriched <- EtlPipeline.processEvents[F](
                      adapterRegistry,
                      registry,
                      igluClient,
                      processor,
                      etlTstamp,
                      payload,
                      FeatureFlags.toCommon(featureFlags),
                      invalidCount
                    )
      } yield (enriched, collectorTstamp)

    result.handleErrorWith(sendToSentry[F](row, sentry, processor, collectorTstamp))
  }

  /** Stringify `ThriftLoader` result for debugging purposes */
  def payloadToString(payload: ValidatedNel[BadRow.CPFormatViolation, Option[CollectorPayload]]): String =
    payload.fold(_.asJson.noSpaces, _.map(_.toBadRowPayload.asJson.noSpaces).getOrElse("None"))

  /** Log an error, turn the problematic `CollectorPayload` into `BadRow` and notify Sentry if configured */
  def sendToSentry[F[_]: Sync: Clock](
    original: Array[Byte],
    sentry: Option[SentryClient],
    processor: Processor,
    collectorTstamp: Option[Long]
  )(
    error: Throwable
  ): F[Result] =
    for {
      _ <- Logger[F].error("Runtime exception during payload enrichment. CollectorPayload converted to generic_error and ack'ed")
      now <- Clock[F].realTime(TimeUnit.MILLISECONDS).map(Instant.ofEpochMilli)
      badRow = genericBadRow(original, now, error, processor)
      _ <- sentry match {
             case Some(client) =>
               Sync[F].delay(client.sendException(error))
             case None =>
               Sync[F].unit
           }
    } yield (List(badRow.invalid), collectorTstamp)

  /** Build a `generic_error` bad row for unhandled runtime errors */
  def genericBadRow(
    row: Array[Byte],
    time: Instant,
    error: Throwable,
    processor: Processor
  ): BadRow.GenericError = {
    val base64 = new String(Base64.getEncoder.encode(row))
    val rawPayload = BadRowPayload.RawPayload(base64)
    val failure = Failure.GenericFailure(time, NonEmptyList.one(ConversionUtils.cleanStackTrace(error)))
    BadRow.GenericError(processor, failure, rawPayload)
  }

  def sinkChunk[F[_]: Concurrent: Parallel, A](
    chunk: List[Result],
    env: Environment[F, A]
  ): F[Unit] = {
    val (bad, enriched) =
      chunk
        .flatMap(_._1)
        .map(_.toEither)
        .separate

    val (moreBad, good) = enriched.map { e =>
      serializeEnriched(e, env.processor, env.streamsSettings.maxRecordSize)
        .map(bytes => (e, AttributedData(bytes, env.goodPartitionKey(e), env.goodAttributes(e))))
    }.separate

    val allBad = (bad ++ moreBad).map(badRowResize(env, _))

    List(
      sinkGood(
        good,
        env.sinkGood,
        env.metrics.goodCount,
        env.metadata.observe,
        env.sinkPii,
        env.piiPartitionKey,
        env.piiAttributes,
        env.processor,
        env.streamsSettings.maxRecordSize
      ) *> env.metrics.enrichLatency(chunk.headOption.flatMap(_._2)),
      sinkBad(allBad, env.sinkBad, env.metrics.badCount)
    ).parSequence_
  }

  def sinkGood[F[_]: Sync](
    good: List[(EnrichedEvent, AttributedData[Array[Byte]])],
    sink: AttributedByteSink[F],
    incMetrics: Int => F[Unit],
    metadata: List[EnrichedEvent] => F[Unit],
    piiSink: Option[AttributedByteSink[F]],
    piiPartitionKey: EnrichedEvent => String,
    piiAttributes: EnrichedEvent => Map[String, String],
    processor: Processor,
    maxRecordSize: Int
  ): F[Unit] = {
    val enriched = good.map(_._1)
    val serialized = good.map(_._2)
    sink(serialized) *> incMetrics(good.size) *> metadata(enriched) *> sinkPii(enriched,
                                                                               piiSink,
                                                                               piiPartitionKey,
                                                                               piiAttributes,
                                                                               processor,
                                                                               maxRecordSize
    )
  }

  def sinkBad[F[_]: Monad](
    bad: List[Array[Byte]],
    sink: ByteSink[F],
    incMetrics: Int => F[Unit]
  ): F[Unit] =
    sink(bad) *> incMetrics(bad.size)

  def sinkPii[F[_]: Sync](
    enriched: List[EnrichedEvent],
    maybeSink: Option[AttributedByteSink[F]],
    partitionKey: EnrichedEvent => String,
    attributes: EnrichedEvent => Map[String, String],
    processor: Processor,
    maxRecordSize: Int
  ): F[Unit] =
    maybeSink match {
      case Some(sink) =>
        val (bad, serialized) =
          enriched
            .flatMap(ConversionUtils.getPiiEvent(processor, _))
            .map(e => serializeEnriched(e, processor, maxRecordSize).map(AttributedData(_, partitionKey(e), attributes(e))))
            .separate
        val logging =
          if (bad.nonEmpty)
            Logger[F].error(s"${bad.size} PII events couldn't get sent because they are too big")
          else
            Sync[F].unit
        sink(serialized) *> logging
      case None =>
        Sync[F].unit
    }

  def serializeEnriched(
    enriched: EnrichedEvent,
    processor: Processor,
    maxRecordSize: Int
  ): Either[BadRow, Array[Byte]] = {
    val asStr = ConversionUtils.tabSeparatedEnrichedEvent(enriched)
    val asBytes = asStr.getBytes(UTF_8)
    val size = asBytes.length
    if (size > maxRecordSize) {
      val msg = s"event passed enrichment but then exceeded the maximum allowed size $maxRecordSize bytes"
      val br = BadRow
        .SizeViolation(
          processor,
          Failure.SizeViolation(Instant.now(), maxRecordSize, size, msg),
          BadRowPayload.RawPayload(asStr.take(maxRecordSize * 8 / 10))
        )
      Left(br)
    } else Right(asBytes)
  }

  /**
   * Check if plain bad row (such as `enrichment_failure`) exceeds the `MaxRecordSize`
   * If it does - turn into size violation with trimmed
   */
  def badRowResize[F[_], A](env: Environment[F, A], badRow: BadRow): Array[Byte] = {
    val asStr = badRow.compact
    val originalBytes = asStr.getBytes(UTF_8)
    val size = originalBytes.size
    val maxRecordSize = env.streamsSettings.maxRecordSize
    if (size > maxRecordSize) {
      val msg = s"event failed enrichment, but resulting bad row exceeds allowed size $maxRecordSize"
      BadRow
        .SizeViolation(
          env.processor,
          Failure.SizeViolation(Instant.now(), maxRecordSize, size, msg),
          BadRowPayload.RawPayload(asStr.take(maxRecordSize / 10))
        )
        .compact
        .getBytes(UTF_8)
    } else originalBytes
  }

  /**
   * This is the machinery needed to make sure that no chunk is sunk without
   * being checkpointed when the app terminates.
   *
   * The stream runs on a separate fiber so that we can manually handle SIGINT.
   *
   * We use a queue as a level of indirection between the stream of enriched events and the sink + checkpointing.
   * When we receive a SIGINT or exception then we terminate the fiber by pushing a `None` to the queue.
   *
   * The stream is only canc rrelled after the sink + checkpointing have been allowed to finish cleanly.
   * We must not terminate the source any earlier, because this would shutdown the kinesis scheduler too early,
   * and then we would not be able to checkpoint the outstanding records.
   */
  private def runWithShutdown[F[_]: Concurrent: Sync: Timer, A](
    enriched: Stream[F, List[(A, Result)]],
    sinkAndCheckpoint: Pipe[F, List[(A, Result)], Unit]
  ): F[Unit] =
    Queue.synchronousNoneTerminated[F, List[(A, Result)]].flatMap { queue =>
      queue.dequeue
        .through(sinkAndCheckpoint)
        .concurrently(enriched.evalMap(x => queue.enqueue1(Some(x))).onFinalize(queue.enqueue1(None)))
        .compile
        .drain
        .start
        .bracketCase(_.join) {
          case (_, ExitCase.Completed) =>
            // The source has completed "naturally", e.g. processed all input files in the directory
            Sync[F].unit
          case (fiber, ExitCase.Canceled) =>
            // SIGINT received. We wait for the enriched events already in the queue to get sunk and checkpointed
            terminateStream(queue, fiber)
          case (fiber, ExitCase.Error(e)) =>
            // Runtime exception in the stream of enriched events.
            // We wait for the enriched events already in the queue to get sunk and checkpointed.
            // We then raise the original exception
            Logger[F].error(e)("Unexpected error in enrich") *>
              terminateStream(queue, fiber).handleErrorWith { e2 =>
                Logger[F].error(e2)("Error when terminating the stream")
              } *> Sync[F].raiseError(e)
        }
    }

  private def terminateStream[F[_]: Concurrent: Sync: Timer, A](queue: NoneTerminatedQueue[F, A], fiber: Fiber[F, Unit]): F[Unit] =
    for {
      timeout <- Sync[F].pure(5.minutes)
      _ <- Logger[F].warn(s"Terminating enrich stream. Waiting $timeout for it to complete")
      _ <- queue.enqueue1(None)
      _ <- fiber.join.timeoutTo(timeout, Logger[F].warn(s"Stream not complete after $timeout, canceling") *> fiber.cancel)
    } yield ()
}
