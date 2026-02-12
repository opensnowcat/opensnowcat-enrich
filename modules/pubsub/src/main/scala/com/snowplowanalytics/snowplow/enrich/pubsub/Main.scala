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
package com.snowplowanalytics.snowplow.enrich.pubsub

import java.util.concurrent.{Executors, TimeUnit}

import scala.concurrent.ExecutionContext

import cats.Parallel
import cats.implicits._

import cats.effect.{ExitCode, IO, IOApp, Resource, Sync}

import com.permutive.pubsub.consumer.ConsumerRecord

import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.{BlobStorageClients, Cloud}
import com.snowplowanalytics.snowplow.enrich.common.fs2.Run
import com.snowplowanalytics.snowplow.enrich.common.fs2.io.Clients.Client

import com.snowplowanalytics.snowplow.enrich.gcp.GcsClient

import com.snowplowanalytics.snowplow.enrich.pubsub.generated.BuildInfo

object Main extends IOApp {

  /**
   * The maximum size of a serialized payload that can be written to pubsub.
   *
   *  Equal to 6.9 MB. The message will be base64 encoded by the underlying library, which brings the
   *  encoded message size to near 10 MB, which is the maximum allowed for PubSub.
   */
  private val MaxRecordSize = 6900000

  // Blocking ExecutionContext for I/O operations
  private val executionContextResource: Resource[IO, ExecutionContext] =
    Resource
      .make(IO {
        val poolSize = math.max(2, Runtime.getRuntime.availableProcessors())
        Executors.newFixedThreadPool(poolSize)
      })(pool =>
        IO {
          pool.shutdown()
          pool.awaitTermination(10, TimeUnit.SECONDS)
          ()
        }
      )
      .map(ExecutionContext.fromExecutorService)

  def run(args: List[String]): IO[ExitCode] =
    executionContextResource.use { blockingEC =>
      Run.run[IO, ConsumerRecord[IO, Array[Byte]]](
        args,
        BuildInfo.name,
        BuildInfo.version,
        BuildInfo.description,
        blockingEC,
        cliConfig => IO.pure(cliConfig),
        (input, _) => Source.init[IO](input),
        out => Sink.initAttributed(out),
        out => Sink.initAttributed(out),
        out => Sink.init(out),
        checkpoint,
        createBlobStorageClient,
        _.value,
        MaxRecordSize,
        Some(Cloud.Gcp),
        None
      )
    }

  private def checkpoint[F[_]: Parallel: Sync](records: List[ConsumerRecord[F, Array[Byte]]]): F[Unit] =
    records.parTraverse_(_.ack)

  private def createBlobStorageClient(conf: BlobStorageClients): List[Resource[IO, Client[IO]]] = {
    val gcs = if (conf.gcs) Some(Resource.eval(GcsClient.mk[IO])) else None
    List(gcs).flatten
  }
}
