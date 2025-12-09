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

import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext

import cats.{Applicative, Parallel}
import cats.implicits._

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.kernel.Resource

import fs2.kafka.CommittableConsumerRecord

import com.snowplowanalytics.snowplow.enrich.aws.S3Client

import com.snowplowanalytics.snowplow.enrich.gcp.GcsClient

import com.snowplowanalytics.snowplow.enrich.azure.AzureStorageClient

import com.snowplowanalytics.snowplow.enrich.common.fs2.Run
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.BlobStorageClients
import com.snowplowanalytics.snowplow.enrich.common.fs2.io.Clients.Client

import com.snowplowanalytics.snowplow.enrich.kafka.generated.BuildInfo

object Main extends IOApp {

  // Kafka records must not exceed 1MB
  private val MaxRecordSize = 1000000

  // Resource-managed blocking ExecutionContext for I/O operations
  private val executionContextResource: Resource[IO, ExecutionContext] =
    Resource
      .make(IO {
        val poolSize = math.max(2, Runtime.getRuntime.availableProcessors())
        Executors.newFixedThreadPool(poolSize)
      })(pool =>
        IO {
          pool.shutdown()
          pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
          ()
        }
      )
      .map(ExecutionContext.fromExecutorService)

  def run(args: List[String]): IO[ExitCode] =
    executionContextResource.use { blockingEC =>
      Run.run[IO, CommittableConsumerRecord[IO, String, Array[Byte]]](
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
        _.record.value,
        MaxRecordSize,
        None,
        None
      )
    }

  private def checkpoint[F[_]: Applicative: Parallel](records: List[CommittableConsumerRecord[F, String, Array[Byte]]]): F[Unit] =
    if (records.isEmpty) Applicative[F].unit
    else
      records
        .groupBy(_.record.partition)
        .mapValues(_.maxBy(_.record.offset))
        .values
        .toList
        .parTraverse_(_.offset.commit)

  private def createBlobStorageClient(conf: BlobStorageClients): List[Resource[IO, Client[IO]]] = {
    val gcs = if (conf.gcs) Some(Resource.eval(GcsClient.mk[IO])) else None
    val aws = if (conf.s3) Some(S3Client.mk[IO]) else None
    val azure = conf.azureStorage.map(s => AzureStorageClient.mk[IO](s))
    List(gcs, aws, azure).flatten
  }
}
