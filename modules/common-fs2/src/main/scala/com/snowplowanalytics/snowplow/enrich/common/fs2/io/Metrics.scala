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
package com.snowplowanalytics.snowplow.enrich.common.fs2.io

import cats.implicits._
import cats.{Applicative, Monad}
import cats.effect.concurrent.Ref
import cats.effect.{Async, Blocker, Clock, ConcurrentEffect, ContextShift, Resource, Sync, Timer}

import fs2.Stream

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}

import org.typelevel.log4cats.slf4j.Slf4jLogger

import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.MetricsReporters

trait Metrics[F[_]] {

  /** Send latest metrics to reporter */
  def report: Stream[F, Unit]

  /**
   * Track latency between collector hit and enrichment
   * This function gets current timestamp by itself
   */
  def enrichLatency(collectorTstamp: Option[Long]): F[Unit]

  /** Increment raw payload count */
  def rawCount(nb: Int): F[Unit]

  /** Increment good enriched events */
  def goodCount(nb: Int): F[Unit]

  /** Increment bad events */
  def badCount(nb: Int): F[Unit]

  /** Increment invalid enriched events count */
  def invalidCount: F[Unit]

  /** If remote adapters are enabled, increment count of successful http requests to remote adapters */
  def remoteAdaptersSuccessCount: F[Unit]

  /** If remote adapters are enabled, increment count of failed http requests to remote adapters except timeouts */
  def remoteAdaptersFailureCount: F[Unit]

  /** If remote adapters are enabled, increment count of timed out http requests to remote adapters */
  def remoteAdaptersTimeoutCount: F[Unit]
}

object Metrics {

  val LoggerName = "enrich.metrics"
  val LatencyGaugeName = "latency"
  val RawCounterName = "raw"
  val GoodCounterName = "good"
  val BadCounterName = "bad"
  val InvalidCounterName = "invalid_enriched"
  val RemoteAdaptersSuccessCounterName = "remote_adapters_success"
  val RemoteAdaptersFailureCounterName = "remote_adapters_failure"
  val RemoteAdaptersTimeoutCounterName = "remote_adapters_timeout"

  final case class MetricSnapshot(
    enrichLatency: Option[Long], // milliseconds
    rawCount: Int,
    goodCount: Int,
    badCount: Int,
    invalidCount: Int,
    remoteAdaptersSuccessCount: Option[Int],
    remoteAdaptersFailureCount: Option[Int],
    remoteAdaptersTimeoutCount: Option[Int]
  )

  trait Reporter[F[_]] {
    def report(snapshot: MetricSnapshot): F[Unit]
  }

  def build[F[_]: ContextShift: ConcurrentEffect: Timer](
    blocker: Blocker,
    config: MetricsReporters,
    remoteAdaptersEnabled: Boolean
  ): F[Metrics[F]] =
    config match {
      case MetricsReporters(None, None, _) => noop[F].pure[F]
      case MetricsReporters(statsd, stdout, _) => impl[F](blocker, statsd, stdout, remoteAdaptersEnabled)
    }

  private def impl[F[_]: ContextShift: ConcurrentEffect: Timer](
    blocker: Blocker,
    statsd: Option[MetricsReporters.StatsD],
    stdout: Option[MetricsReporters.Stdout],
    remoteAdaptersEnabled: Boolean
  ): F[Metrics[F]] =
    for {
      refsStatsd <- MetricRefs.init[F](remoteAdaptersEnabled)
      refsStdout <- MetricRefs.init[F](remoteAdaptersEnabled)
    } yield new Metrics[F] {
      def report: Stream[F, Unit] = {

        val rep1 = statsd
          .map { config =>
            reporterStream(StatsDReporter.make[F](blocker, config), refsStatsd, config.period, remoteAdaptersEnabled)
          }
          .getOrElse(Stream.never[F])

        val rep2 = stdout
          .map { config =>
            reporterStream(Resource.eval(stdoutReporter(config)), refsStdout, config.period, remoteAdaptersEnabled)
          }
          .getOrElse(Stream.never[F])

        rep1.concurrently(rep2)
      }

      def enrichLatency(collectorTstamp: Option[Long]): F[Unit] =
        collectorTstamp match {
          case Some(tstamp) =>
            for {
              now <- Clock[F].realTime(MILLISECONDS)
              _ <- refsStatsd.latency.set(Some(now - tstamp))
              _ <- refsStdout.latency.set(Some(now - tstamp))
            } yield ()
          case None =>
            Sync[F].unit
        }

      def rawCount(nb: Int): F[Unit] =
        refsStatsd.rawCount.update(_ + nb) *>
          refsStdout.rawCount.update(_ + nb)

      def goodCount(nb: Int): F[Unit] =
        refsStatsd.goodCount.update(_ + nb) *>
          refsStdout.goodCount.update(_ + nb)

      def badCount(nb: Int): F[Unit] =
        refsStatsd.badCount.update(_ + nb) *>
          refsStdout.badCount.update(_ + nb)

      def invalidCount: F[Unit] =
        refsStatsd.invalidCount.update(_ + 1) *>
          refsStdout.invalidCount.update(_ + 1)

      def remoteAdaptersSuccessCount: F[Unit] =
        refsStatsd.remoteAdaptersSuccessCount.update(_.map(_ + 1)) *> refsStdout.remoteAdaptersSuccessCount.update(_.map(_ + 1))

      def remoteAdaptersFailureCount: F[Unit] =
        refsStatsd.remoteAdaptersFailureCount.update(_.map(_ + 1)) *> refsStdout.remoteAdaptersFailureCount.update(_.map(_ + 1))

      def remoteAdaptersTimeoutCount: F[Unit] =
        refsStatsd.remoteAdaptersTimeoutCount.update(_.map(_ + 1)) *> refsStdout.remoteAdaptersTimeoutCount.update(_.map(_ + 1))
    }

  private final case class MetricRefs[F[_]](
    latency: Ref[F, Option[Long]], // milliseconds
    rawCount: Ref[F, Int],
    goodCount: Ref[F, Int],
    badCount: Ref[F, Int],
    invalidCount: Ref[F, Int],
    remoteAdaptersSuccessCount: Ref[F, Option[Int]],
    remoteAdaptersFailureCount: Ref[F, Option[Int]],
    remoteAdaptersTimeoutCount: Ref[F, Option[Int]]
  )

  private object MetricRefs {
    def init[F[_]: Sync](remoteAdaptersEnabled: Boolean): F[MetricRefs[F]] =
      for {
        latency <- Ref.of[F, Option[Long]](None)
        rawCounter <- Ref.of[F, Int](0)
        goodCounter <- Ref.of[F, Int](0)
        badCounter <- Ref.of[F, Int](0)
        invalidCounter <- Ref.of[F, Int](0)
        remoteAdaptersSuccessCounter <- Ref.of[F, Option[Int]](if (remoteAdaptersEnabled) Some(0) else None)
        remoteAdaptersFailureCounter <- Ref.of[F, Option[Int]](if (remoteAdaptersEnabled) Some(0) else None)
        remoteAdaptersTimeoutCounter <- Ref.of[F, Option[Int]](if (remoteAdaptersEnabled) Some(0) else None)
      } yield MetricRefs(
        latency,
        rawCounter,
        goodCounter,
        badCounter,
        invalidCounter,
        remoteAdaptersSuccessCounter,
        remoteAdaptersFailureCounter,
        remoteAdaptersTimeoutCounter
      )

    def snapshot[F[_]: Monad](refs: MetricRefs[F], remoteAdaptersEnabled: Boolean): F[MetricSnapshot] =
      for {
        latency <- refs.latency.getAndSet(None)
        rawCount <- refs.rawCount.getAndSet(0)
        goodCount <- refs.goodCount.getAndSet(0)
        badCount <- refs.badCount.getAndSet(0)
        invalidCount <- refs.invalidCount.getAndSet(0)
        remoteAdaptersSuccessCount <- refs.remoteAdaptersSuccessCount.getAndSet(if (remoteAdaptersEnabled) Some(0) else None)
        remoteAdaptersFailureCount <- refs.remoteAdaptersFailureCount.getAndSet(if (remoteAdaptersEnabled) Some(0) else None)
        remoteAdaptersTimeoutCount <- refs.remoteAdaptersTimeoutCount.getAndSet(if (remoteAdaptersEnabled) Some(0) else None)
      } yield MetricSnapshot(latency,
                             rawCount,
                             goodCount,
                             badCount,
                             invalidCount,
                             remoteAdaptersSuccessCount,
                             remoteAdaptersFailureCount,
                             remoteAdaptersTimeoutCount
      )
  }

  def reporterStream[F[_]: Sync: Timer: ContextShift](
    reporter: Resource[F, Reporter[F]],
    metrics: MetricRefs[F],
    period: FiniteDuration,
    remoteAdaptersEnabled: Boolean
  ): Stream[F, Unit] =
    for {
      rep <- Stream.resource(reporter)
      _ <- Stream.fixedDelay[F](period)
      snapshot <- Stream.eval(MetricRefs.snapshot(metrics, remoteAdaptersEnabled))
      _ <- Stream.eval(rep.report(snapshot))
    } yield ()

  def stdoutReporter[F[_]: Sync: ContextShift: Timer](
    config: MetricsReporters.Stdout
  ): F[Reporter[F]] =
    for {
      logger <- Slf4jLogger.fromName[F](LoggerName)
    } yield new Reporter[F] {
      def report(snapshot: MetricSnapshot): F[Unit] =
        for {
          _ <- logger.info(s"${MetricsReporters.normalizeMetric(config.prefix, RawCounterName)} = ${snapshot.rawCount}")
          _ <- logger.info(s"${MetricsReporters.normalizeMetric(config.prefix, GoodCounterName)} = ${snapshot.goodCount}")
          _ <- logger.info(s"${MetricsReporters.normalizeMetric(config.prefix, BadCounterName)} = ${snapshot.badCount}")
          _ <- logger.info(s"${MetricsReporters.normalizeMetric(config.prefix, InvalidCounterName)} = ${snapshot.invalidCount}")
          _ <- snapshot.enrichLatency
                 .map(latency => logger.info(s"${MetricsReporters.normalizeMetric(config.prefix, LatencyGaugeName)} = $latency"))
                 .getOrElse(Sync[F].unit)
          _ <- snapshot.remoteAdaptersSuccessCount
                 .map(cnt => logger.info(s"${MetricsReporters.normalizeMetric(config.prefix, RemoteAdaptersSuccessCounterName)} = $cnt"))
                 .getOrElse(Applicative[F].unit)
          _ <- snapshot.remoteAdaptersFailureCount
                 .map(cnt => logger.info(s"${MetricsReporters.normalizeMetric(config.prefix, RemoteAdaptersFailureCounterName)} = $cnt"))
                 .getOrElse(Applicative[F].unit)
          _ <- snapshot.remoteAdaptersTimeoutCount
                 .map(cnt => logger.info(s"${MetricsReporters.normalizeMetric(config.prefix, RemoteAdaptersTimeoutCounterName)} = $cnt"))
                 .getOrElse(Applicative[F].unit)
        } yield ()
    }

  def noop[F[_]: Async]: Metrics[F] =
    new Metrics[F] {
      def report: Stream[F, Unit] = Stream.never[F]
      def enrichLatency(collectorTstamp: Option[Long]): F[Unit] = Applicative[F].unit
      def rawCount(nb: Int): F[Unit] = Applicative[F].unit
      def goodCount(nb: Int): F[Unit] = Applicative[F].unit
      def badCount(nb: Int): F[Unit] = Applicative[F].unit
      def invalidCount: F[Unit] = Applicative[F].unit
      def remoteAdaptersSuccessCount: F[Unit] = Applicative[F].unit
      def remoteAdaptersFailureCount: F[Unit] = Applicative[F].unit
      def remoteAdaptersTimeoutCount: F[Unit] = Applicative[F].unit
    }
}
