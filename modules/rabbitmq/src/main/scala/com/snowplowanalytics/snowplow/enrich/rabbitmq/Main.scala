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
package com.snowplowanalytics.snowplow.enrich.rabbitmq

import cats.Parallel
import cats.implicits._

import cats.effect.{ExitCode, IO, IOApp, Resource, Sync, SyncIO}

import java.util.concurrent.{Executors, TimeUnit}

import scala.concurrent.ExecutionContext

import com.snowplowanalytics.snowplow.enrich.common.fs2.Run

import com.snowplowanalytics.snowplow.enrich.rabbitmq.generated.BuildInfo

object Main extends IOApp.WithContext {

  private val MaxRecordSize = 128000000

  /**
   * An execution context matching the cats effect IOApp default. We create it explicitly so we can
   * also use it for our Blaze client.
   */
  override protected val executionContextResource: Resource[SyncIO, ExecutionContext] = {
    val poolSize = math.max(2, Runtime.getRuntime().availableProcessors())
    Resource
      .make(SyncIO(Executors.newFixedThreadPool(poolSize)))(pool =>
        SyncIO {
          pool.shutdown()
          pool.awaitTermination(10, TimeUnit.SECONDS)
          ()
        }
      )
      .map(ExecutionContext.fromExecutorService)
  }

  def run(args: List[String]): IO[ExitCode] =
    Run.run[IO, Record[IO]](
      args,
      BuildInfo.name,
      """(\d.\d.\d(-\w*\d*)?)""".r.findFirstIn(BuildInfo.version).getOrElse(BuildInfo.version),
      BuildInfo.description,
      executionContext,
      (_, cliConfig) => IO(cliConfig),
      (blocker, input, _) => Source.init(blocker, input),
      (blocker, out) => Sink.initAttributed(blocker, out),
      (blocker, out) => Sink.initAttributed(blocker, out),
      (blocker, out) => Sink.init(blocker, out),
      checkpoint,
      Nil,
      _.data,
      MaxRecordSize,
      None,
      None
    )

  private def checkpoint[F[_]: Parallel: Sync](records: List[Record[F]]): F[Unit] =
    records.parTraverse_(_.ack)
}
