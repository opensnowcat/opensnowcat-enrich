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

package com.snowplowanalytics.snowplow.enrich.kafka

import java.util.concurrent.{Executors, TimeUnit}
import scala.concurrent.ExecutionContext
import cats.{Applicative, Parallel}
import cats.implicits._
import cats.effect.{ExitCode, IO, IOApp, Resource, SyncIO}
import fs2.kafka.CommittableConsumerRecord
import com.snowplowanalytics.snowplow.enrich.common.fs2.Run
import com.snowplowanalytics.snowplow.enrich.kafka.generated.BuildInfo

object Main extends IOApp.WithContext {

  // Kafka records must not exceed 1MB
  private val MaxRecordSize = 1000000

  /**
   * An execution context matching the cats effect IOApp default. We create it explicitly so we can
   * also use it for our Blaze client.
   */
  override protected val executionContextResource: Resource[SyncIO, ExecutionContext] = {
    val poolSize = math.max(2, Runtime.getRuntime.availableProcessors())
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
    Run.run[IO, CommittableConsumerRecord[IO, String, Array[Byte]]](
      args,
      BuildInfo.name,
      BuildInfo.version,
      BuildInfo.description,
      executionContext,
      (_, cliConfig) => IO(cliConfig),
      (_, input, _) => Source.init[IO](input),
      (blocker, out) => Sink.initAttributed(blocker, out),
      (blocker, out) => Sink.initAttributed(blocker, out),
      (blocker, out) => Sink.init(blocker, out),
      checkpoint,
      List.empty,
      _.record.value,
      MaxRecordSize,
      None,
      None
    )

  private def checkpoint[F[_]: Applicative: Parallel](records: List[CommittableConsumerRecord[F, String, Array[Byte]]]): F[Unit] =
    if (records.isEmpty) Applicative[F].unit
    else
      records
        .groupBy(_.record.partition)
        .mapValues(_.maxBy(_.record.offset))
        .values
        .toList
        .parTraverse_(_.offset.commit)
}
