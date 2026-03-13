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

import scala.concurrent.duration.FiniteDuration

import cats.effect.kernel.Temporal
import cats.effect.IO

import fs2.Stream

package object test {

  implicit class StreamOps[F[_], A](s: Stream[F, A]) {

    /** Halting a stream after specified period of time */
    def haltAfter(after: FiniteDuration)(implicit T: Temporal[F]): Stream[F, A] =
      Stream.exec(Temporal[F].sleep(after)).mergeHaltL(s)
  }

  implicit class StreamIoOps[A](s: Stream[IO, A]) {

    /** Run test [[HttpServer]] in parallel with the stream */
    def withHttp: Stream[IO, A] =
      Stream.resource(HttpServer.resource).flatMap(_ => s)
  }
}
