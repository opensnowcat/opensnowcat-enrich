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
package com.snowplowanalytics.snowplow.enrich.common.fs2.test

import java.util.concurrent.TimeUnit

import cats.Monad
import cats.syntax.flatMap._

import cats.effect.concurrent.Ref
import cats.effect.{Clock, Sync}

import fs2.Stream

import com.snowplowanalytics.snowplow.enrich.common.fs2.io.Metrics

/** Metrics container for testing */
case class Counter(
  raw: Long,
  good: Long,
  bad: Long,
  latency: Option[Long],
  invalid: Long,
  remoteAdaptersSuccessCount: Option[Long],
  remoteAdaptersFailureCount: Option[Long],
  remoteAdaptersTimeoutCount: Option[Long]
)

object Counter {
  val empty: Counter = Counter(0L, 0L, 0L, None, 0L, None, None, None)

  def make[F[_]: Sync]: F[Ref[F, Counter]] =
    Ref.of[F, Counter](empty)

  /** Create a pure metrics with mutable state */
  def mkCounterMetrics[F[_]: Monad: Clock](ref: Ref[F, Counter]): Metrics[F] =
    new Metrics[F] {
      def report: Stream[F, Unit] = Stream.empty.covary[F]

      def enrichLatency(collectorTstamp: Option[Long]): F[Unit] =
        Clock[F].realTime(TimeUnit.MILLISECONDS).flatMap { now =>
          ref.update(_.copy(latency = collectorTstamp.map(ct => now - ct)))
        }

      def rawCount(nb: Int): F[Unit] =
        ref.update(cnt => cnt.copy(raw = cnt.raw + nb))

      def goodCount(nb: Int): F[Unit] =
        ref.update(cnt => cnt.copy(good = cnt.good + nb))

      def badCount(nb: Int): F[Unit] =
        ref.update(cnt => cnt.copy(bad = cnt.bad + nb))

      def invalidCount: F[Unit] =
        ref.update(cnt => cnt.copy(invalid = cnt.invalid + 1))

      def remoteAdaptersSuccessCount: F[Unit] =
        ref.update(cnt => cnt.copy(remoteAdaptersSuccessCount = cnt.remoteAdaptersSuccessCount.map(_ + 1)))

      def remoteAdaptersFailureCount: F[Unit] =
        ref.update(cnt => cnt.copy(remoteAdaptersFailureCount = cnt.remoteAdaptersFailureCount.map(_ + 1)))

      def remoteAdaptersTimeoutCount: F[Unit] =
        ref.update(cnt => cnt.copy(remoteAdaptersTimeoutCount = cnt.remoteAdaptersTimeoutCount.map(_ + 1)))
    }
}
