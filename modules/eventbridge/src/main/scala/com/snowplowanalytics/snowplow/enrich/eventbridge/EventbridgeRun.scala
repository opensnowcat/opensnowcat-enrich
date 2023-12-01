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
package com.snowplowanalytics.snowplow.enrich.eventbridge

import cats.Parallel
import cats.effect.{Clock, ConcurrentEffect, ContextShift, ExitCode, Sync, Timer}
import cats.implicits._
import com.snowplowanalytics.snowplow.enrich.common.fs2.Run
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Cloud
import com.snowplowanalytics.snowplow.enrich.eventbridge.generated.BuildInfo
import fs2.aws.kinesis.CommittableRecord
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import software.amazon.kinesis.exceptions.ShutdownException

import scala.concurrent.ExecutionContext

object EventbridgeRun {

  // Kinesis records must not exceed 1 MB
  private val MaxRecordSize = 1024 * 1024

  private implicit def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  def run[F[_]: Clock: ConcurrentEffect: ContextShift: Parallel: Timer](args: List[String], ec: ExecutionContext): F[ExitCode] =
    Run.run[F, CommittableRecord](
      args,
      BuildInfo.name,
      BuildInfo.version,
      BuildInfo.description,
      ec,
      com.snowplowanalytics.snowplow.enrich.kinesis.DynamoDbConfig.updateCliConfig[F],
      com.snowplowanalytics.snowplow.enrich.kinesis.Source.init[F],
      (blocker, out) => Sink.initAttributed(blocker, out),
      (blocker, out) => Sink.initAttributed(blocker, out),
      (blocker, out) => Sink.init(blocker, out),
      checkpoint[F],
      List(_ => com.snowplowanalytics.snowplow.enrich.kinesis.S3Client.mk[F]),
      com.snowplowanalytics.snowplow.enrich.kinesis.KinesisRun.getPayload,
      MaxRecordSize,
      Some(Cloud.Aws),
      getRuntimeRegion
    )

  /** For each shard, the record with the biggest sequence number is found, and checkpointed. */
  private def checkpoint[F[_]: Parallel: Sync: Timer](records: List[CommittableRecord]): F[Unit] =
    records
      .groupBy(_.shardId)
      .foldLeft(List.empty[CommittableRecord]) { (acc, shardRecords) =>
        shardRecords._2
          .reduceLeft[CommittableRecord] { (biggest, record) =>
            if (record.sequenceNumber > biggest.sequenceNumber)
              record
            else
              biggest
          } :: acc
      }
      .parTraverse_ { record =>
        record.checkpoint
          .recoverWith {
            case _: ShutdownException =>
              // The ShardRecordProcessor instance has been shutdown. This just means another KCL
              // worker has stolen our lease. It is expected during autoscaling of instances, and is
              // safe to ignore.
              Logger[F].warn(s"Skipping checkpointing of shard ${record.shardId} because this worker no longer owns the lease")

            case _: IllegalArgumentException if record.isLastInShard =>
              // See https://github.com/snowplow/enrich/issues/657
              // This can happen at the shard end when KCL no longer allows checkpointing of the last record in the shard.
              // We need to release the semaphore, so that fs2-aws handles checkpointing the end of the shard.
              Logger[F].warn(
                s"Checkpointing failed on last record in shard. Ignoring error and instead try checkpointing of the shard end"
              ) *>
                Sync[F].delay(record.lastRecordSemaphore.release())

            case _: IllegalArgumentException if record.lastRecordSemaphore.availablePermits === 0 =>
              // See https://github.com/snowplow/enrich/issues/657 and https://github.com/snowplow/enrich/pull/658
              // This can happen near the shard end, e.g. the penultimate batch in the shard, when KCL has already enqueued the final record in the shard to the fs2 queue.
              // We must not release the semaphore yet, because we are not ready for fs2-aws to checkpoint the end of the shard.
              // We can safely ignore the exception and move on.
              Logger[F].warn(
                s"Checkpointing failed on a record which was not the last in the shard. Meanwhile, KCL has already enqueued the final record in the shard to the fs2 queue. Ignoring error and instead continue processing towards the shard end"
              )
          }
      }
}
