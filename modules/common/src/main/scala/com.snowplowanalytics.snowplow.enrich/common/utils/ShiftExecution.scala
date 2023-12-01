/*
 * Copyright (c) 2023 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.common.utils

import cats.effect.{ContextShift, Resource, Sync}

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

/**
 * Shifts execution to a dedicated thread pool
 *
 * This is needed for running blocking JDBC operations. Inspired by Doobie's threading model:
 *
 * > The reason for having separate pools for awaiting connections and executing JDBC operations is
 * > liveness - we must avoid the situation where all the threads in the pool are blocked on
 * > acquiring a JDBC connection, meaning that no logical threads are able to make progress and
 * > release the connection they’re currently holding.
 *
 * See https://tpolecat.github.io/doobie/docs/14-Managing-Connections.html#about-threading
 *
 * This typeclass is a bit of a hack while we are supporting Id and IO instances. It should be
 * removed when we deprecate stream-enrich.
 */
trait ShiftExecution[F[_]] {

  def shift[A](f: F[A]): F[A]

}

object ShiftExecution {

  // A single thread is adequate because our Hikari connection pool has a single connection
  def ofSingleThread[F[_]: ContextShift: Sync]: Resource[F, ShiftExecution[F]] =
    for {
      es <- Resource.make(Sync[F].delay(Executors.newSingleThreadExecutor))(e => Sync[F].delay(e.shutdown))
      ec <- Resource.eval(Sync[F].delay(ExecutionContext.fromExecutorService(es)))
    } yield new ShiftExecution[F] {
      def shift[A](f: F[A]): F[A] =
        ContextShift[F].evalOn(ec)(f)
    }

  // Shifting is not needed in stream-enrich, because all enrichment operations run sequentially on
  // the same thread.
  def noop[F[_]]: ShiftExecution[F] =
    new ShiftExecution[F] {
      override def shift[A](f: F[A]): F[A] =
        f
    }

}
