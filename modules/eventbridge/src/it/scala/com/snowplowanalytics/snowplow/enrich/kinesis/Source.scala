/*
 * Copyright (c) 2021-2021 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.kinesis

import java.util.{Date, UUID}
import java.net.{InetAddress, URI}

import cats.implicits._

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, Resource, Sync, Timer}

import fs2.Stream

import fs2.aws.kinesis.{CommittableRecord, Kinesis, KinesisConsumerSettings}

import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric._

import software.amazon.awssdk.regions.Region
import software.amazon.kinesis.common.{ConfigsBuilder, InitialPositionInStream, InitialPositionInStreamExtended}
import software.amazon.kinesis.coordinator.Scheduler
import software.amazon.kinesis.metrics.MetricsLevel
import software.amazon.kinesis.processor.ShardRecordProcessorFactory
import software.amazon.kinesis.retrieval.polling.PollingConfig
import software.amazon.kinesis.retrieval.fanout.FanOutConfig
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient

import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.{Input, Monitoring}
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Input.Kinesis.InitPosition

object Source {

  def init[F[_]: ConcurrentEffect: ContextShift: Timer](
    blocker: Blocker,
    input: Input,
    monitoring: Monitoring
  ): Stream[F, CommittableRecord] =
    input match {
      case k: Input.Kinesis =>
        k.region.orElse(getRuntimeRegion) match {
          case Some(region) =>
            kinesis(blocker, k, region, monitoring)
          case None =>
            Stream.raiseError[F](new RuntimeException(s"Region not found in the config and in the runtime"))
        }
      case i =>
        Stream.raiseError[F](new IllegalArgumentException(s"Input $i is not Kinesis"))
    }

  def kinesis[F[_]: ConcurrentEffect: ContextShift: Sync: Timer](
    blocker: Blocker,
    kinesisConfig: Input.Kinesis,
    region: String,
    monitoring: Monitoring
  ): Stream[F, CommittableRecord] = {
    val resources =
      for {
        region <- Resource.pure[F, Region](Region.of(region))
        bufferSize <- Resource.eval[F, Int Refined Positive](
                        refineV[Positive](kinesisConfig.bufferSize) match {
                          case Right(mc) => Sync[F].pure(mc)
                          case Left(e) =>
                            Sync[F].raiseError(
                              new IllegalArgumentException(s"${kinesisConfig.bufferSize} can't be refined as positive: $e")
                            )
                        }
                      )
        consumerSettings <- Resource.pure[F, KinesisConsumerSettings](
                              KinesisConsumerSettings(
                                kinesisConfig.streamName,
                                kinesisConfig.appName,
                                bufferSize = bufferSize
                              )
                            )
        kinesisClient <- mkKinesisClient[F](region, kinesisConfig.customEndpoint)
        dynamoClient <- mkDynamoDbClient[F](region, kinesisConfig.dynamodbCustomEndpoint)
        cloudWatchClient <- mkCloudWatchClient[F](region, kinesisConfig.cloudwatchCustomEndpoint)
        kinesis <- Resource.pure[F, Kinesis[F]](
                     Kinesis.create(blocker, scheduler(kinesisClient, dynamoClient, cloudWatchClient, kinesisConfig, monitoring, _))
                   )
      } yield (consumerSettings, kinesis)

    Stream
      .resource(resources)
      .flatMap { case (settings, kinesis) => kinesis.readFromKinesisStream(settings) }
  }

  private def scheduler[F[_]: Sync](
    kinesisClient: KinesisAsyncClient,
    dynamoDbClient: DynamoDbAsyncClient,
    cloudWatchClient: CloudWatchAsyncClient,
    kinesisConfig: Input.Kinesis,
    monitoring: Monitoring,
    recordProcessorFactory: ShardRecordProcessorFactory
  ): F[Scheduler] =
    Sync[F].delay(UUID.randomUUID()).map { uuid =>
      val hostname = InetAddress.getLocalHost().getCanonicalHostName()

      val configsBuilder =
        new ConfigsBuilder(kinesisConfig.streamName,
                           kinesisConfig.appName,
                           kinesisClient,
                           dynamoDbClient,
                           cloudWatchClient,
                           s"$hostname:$uuid",
                           recordProcessorFactory
        )

      val initPositionExtended = kinesisConfig.initialPosition match {
        case InitPosition.Latest =>
          InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.LATEST)
        case InitPosition.TrimHorizon =>
          InitialPositionInStreamExtended.newInitialPosition(InitialPositionInStream.TRIM_HORIZON)
        case InitPosition.AtTimestamp(timestamp) =>
          InitialPositionInStreamExtended.newInitialPositionAtTimestamp(Date.from(timestamp))
      }

      val retrievalConfig =
        configsBuilder.retrievalConfig
          .initialPositionInStreamExtended(initPositionExtended)
          .retrievalSpecificConfig {
            kinesisConfig.retrievalMode match {
              case Input.Kinesis.Retrieval.FanOut =>
                new FanOutConfig(kinesisClient).streamName(kinesisConfig.streamName).applicationName(kinesisConfig.appName)
              case Input.Kinesis.Retrieval.Polling(maxRecords) =>
                new PollingConfig(kinesisConfig.streamName, kinesisClient).maxRecords(maxRecords)
            }
          }

      val metricsConfig = configsBuilder.metricsConfig.metricsLevel {
        if (monitoring.metrics.cloudwatch) MetricsLevel.DETAILED else MetricsLevel.NONE
      }

      new Scheduler(
        configsBuilder.checkpointConfig,
        configsBuilder.coordinatorConfig,
        configsBuilder.leaseManagementConfig,
        configsBuilder.lifecycleConfig,
        metricsConfig,
        configsBuilder.processorConfig,
        retrievalConfig
      )
    }

  private def mkKinesisClient[F[_]: Sync](region: Region, customEndoint: Option[URI]): Resource[F, KinesisAsyncClient] =
    Resource.fromAutoCloseable {
      Sync[F].delay {
        val builder =
          KinesisAsyncClient
            .builder()
            .region(region)
        val customized = customEndoint.map(builder.endpointOverride).getOrElse(builder)
        customized.build
      }
    }

  private def mkDynamoDbClient[F[_]: Sync](region: Region, customEndoint: Option[URI]): Resource[F, DynamoDbAsyncClient] =
    Resource.fromAutoCloseable {
      Sync[F].delay {
        val builder =
          DynamoDbAsyncClient
            .builder()
            .region(region)
        val customized = customEndoint.map(builder.endpointOverride).getOrElse(builder)
        customized.build
      }
    }

  private def mkCloudWatchClient[F[_]: Sync](region: Region, customEndoint: Option[URI]): Resource[F, CloudWatchAsyncClient] =
    Resource.fromAutoCloseable {
      Sync[F].delay {
        val builder =
          CloudWatchAsyncClient
            .builder()
            .region(region)
        val customized = customEndoint.map(builder.endpointOverride).getOrElse(builder)
        customized.build
      }
    }
}
