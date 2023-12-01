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
package com.snowplowanalytics.snowplow.enrich.common.fs2.config

import java.nio.file.{InvalidPathException, Path, Paths}
import java.time.Instant
import java.net.URI
import java.util.UUID

import cats.syntax.either._
import cats.data.NonEmptyList

import scala.concurrent.duration.{Duration, FiniteDuration}

import _root_.io.circe.{Decoder, DecodingFailure, Encoder}
import _root_.io.circe.generic.extras.semiauto._
import _root_.io.circe.config.syntax._

import org.http4s.{ParseFailure, Uri}

import com.snowplowanalytics.snowplow.enrich.common.EtlPipeline.{FeatureFlags => CommonFeatureFlags}
import com.snowplowanalytics.snowplow.enrich.common.adapters._

object io {

  val putRecordsMaxRecords = 500

  import ConfigFile.finiteDurationEncoder

  implicit val javaPathDecoder: Decoder[Path] =
    Decoder[String].emap { s =>
      Either.catchOnly[InvalidPathException](Paths.get(s)).leftMap(_.getMessage)
    }
  implicit val javaPathEncoder: Encoder[Path] =
    Encoder[String].contramap(_.toString)

  implicit val javaUriDecoder: Decoder[URI] =
    Decoder[String].emap { s =>
      Either.catchOnly[IllegalArgumentException](URI.create(s)).leftMap(err => s"error while parsing URI $s: ${err.getMessage}")
    }

  implicit val javaUriEncoder: Encoder[URI] =
    Encoder.encodeString.contramap[URI](_.toString)

  implicit val http4sUriDecoder: Decoder[Uri] =
    Decoder[String].emap(s => Either.catchOnly[ParseFailure](Uri.unsafeFromString(s)).leftMap(_.toString))

  implicit val http4sUriEncoder: Encoder[Uri] =
    Encoder[String].contramap(_.toString)

  import ConfigFile.finiteDurationEncoder

  case class BackoffPolicy(
    minBackoff: FiniteDuration,
    maxBackoff: FiniteDuration,
    maxRetries: Option[Int]
  )
  object BackoffPolicy {
    implicit def backoffPolicyDecoder: Decoder[BackoffPolicy] =
      deriveConfiguredDecoder[BackoffPolicy]
    implicit def backoffPolicyEncoder: Encoder[BackoffPolicy] =
      deriveConfiguredEncoder[BackoffPolicy]
  }

  sealed trait RetryCheckpointing {
    val checkpointBackoff: BackoffPolicy
  }

  case class RabbitMQNode(
    host: String,
    port: Int
  )
  object RabbitMQNode {
    implicit val rabbitMQNodeDecoder: Decoder[RabbitMQNode] = deriveConfiguredDecoder[RabbitMQNode]
    implicit val rabbitMQNodeEncoder: Encoder[RabbitMQNode] = deriveConfiguredEncoder[RabbitMQNode]
  }

  case class RabbitMQConfig(
    nodes: NonEmptyList[RabbitMQNode],
    username: String,
    password: String,
    virtualHost: String,
    connectionTimeout: Int,
    ssl: Boolean,
    internalQueueSize: Int,
    requestedHeartbeat: Int,
    automaticRecovery: Boolean
  )
  object RabbitMQConfig {
    implicit val rabbitMQConfigDecoder: Decoder[RabbitMQConfig] = deriveConfiguredDecoder[RabbitMQConfig]
    implicit val rabbitMQConfigEncoder: Encoder[RabbitMQConfig] = deriveConfiguredEncoder[RabbitMQConfig]
  }

  sealed trait Input

  object Input {

    case class Kafka private (
      topicName: String,
      bootstrapServers: String,
      consumerConf: Map[String, String]
    ) extends Input

    case class Nsq private (
      topic: String,
      channel: String,
      lookupHost: String,
      lookupPort: Int,
      maxBufferQueueSize: Int,
      checkpointBackoff: BackoffPolicy
    ) extends Input
        with RetryCheckpointing

    case class PubSub private (
      subscription: String,
      parallelPullCount: Int,
      maxQueueSize: Int,
      maxRequestBytes: Int,
      maxAckExtensionPeriod: FiniteDuration
    ) extends Input {
      val (project, name) =
        subscription.split("/").toList match {
          case List("projects", project, "subscriptions", name) =>
            (project, name)
          case _ =>
            throw new IllegalArgumentException(s"Subscription format $subscription invalid")
        }
    }
    case class FileSystem(dir: Path) extends Input
    case class Kinesis private (
      appName: String,
      streamName: String,
      region: Option[String],
      initialPosition: Kinesis.InitPosition,
      retrievalMode: Kinesis.Retrieval,
      bufferSize: Int,
      checkpointBackoff: BackoffPolicy,
      customEndpoint: Option[URI],
      dynamodbCustomEndpoint: Option[URI],
      cloudwatchCustomEndpoint: Option[URI]
    ) extends Input
        with RetryCheckpointing
    case class RabbitMQ(
      cluster: RabbitMQConfig,
      queue: String,
      checkpointBackoff: BackoffPolicy
    ) extends Input
        with RetryCheckpointing

    object Kinesis {
      sealed trait InitPosition
      object InitPosition {
        case object Latest extends InitPosition
        case object TrimHorizon extends InitPosition
        case class AtTimestamp(timestamp: Instant) extends InitPosition

        case class InitPositionRaw(`type`: String, timestamp: Option[Instant])
        implicit val initPositionRawDecoder: Decoder[InitPositionRaw] = deriveConfiguredDecoder[InitPositionRaw]

        implicit val initPositionDecoder: Decoder[InitPosition] =
          Decoder.instance { cur =>
            for {
              rawParsed <- cur.as[InitPositionRaw].map(raw => raw.copy(`type` = raw.`type`.toUpperCase))
              initPosition <- rawParsed match {
                                case InitPositionRaw("TRIM_HORIZON", _) =>
                                  TrimHorizon.asRight
                                case InitPositionRaw("LATEST", _) =>
                                  Latest.asRight
                                case InitPositionRaw("AT_TIMESTAMP", Some(timestamp)) =>
                                  AtTimestamp(timestamp).asRight
                                case other =>
                                  DecodingFailure(
                                    s"Initial position $other is not supported. Possible types are TRIM_HORIZON, LATEST and AT_TIMESTAMP (must provide timestamp field)",
                                    cur.history
                                  ).asLeft
                              }
            } yield initPosition
          }
        implicit val initPositionEncoder: Encoder[InitPosition] = deriveConfiguredEncoder[InitPosition]
      }

      sealed trait Retrieval
      object Retrieval {
        case class Polling(maxRecords: Int) extends Retrieval
        case object FanOut extends Retrieval

        case class RetrievalRaw(`type`: String, maxRecords: Option[Int])
        implicit val retrievalRawDecoder: Decoder[RetrievalRaw] = deriveConfiguredDecoder[RetrievalRaw]

        implicit val retrievalDecoder: Decoder[Retrieval] =
          Decoder.instance { cur =>
            for {
              rawParsed <- cur.as[RetrievalRaw].map(raw => raw.copy(`type` = raw.`type`.toUpperCase))
              retrieval <- rawParsed match {
                             case RetrievalRaw("POLLING", Some(maxRecords)) =>
                               Polling(maxRecords).asRight
                             case RetrievalRaw("FANOUT", _) =>
                               FanOut.asRight
                             case other =>
                               DecodingFailure(
                                 s"Retrieval mode $other is not supported. Possible types are FanOut and Polling (must provide maxRecords field)",
                                 cur.history
                               ).asLeft
                           }
            } yield retrieval
          }
        implicit val retrievalEncoder: Encoder[Retrieval] = deriveConfiguredEncoder[Retrieval]
      }

      implicit val kinesisDecoder: Decoder[Kinesis] = deriveConfiguredDecoder[Kinesis]
      implicit val kinesisEncoder: Encoder[Kinesis] = deriveConfiguredEncoder[Kinesis]
    }

    implicit val inputDecoder: Decoder[Input] =
      deriveConfiguredDecoder[Input]
        .emap {
          case s @ PubSub(sub, _, _, _, _) =>
            sub.split("/").toList match {
              case List("projects", _, "subscriptions", _) =>
                s.asRight
              case _ =>
                s"Subscription must conform projects/project-name/subscriptions/subscription-name format, $s given".asLeft
            }
          case Kafka(topicName, bootstrapServers, _) if topicName.isEmpty ^ bootstrapServers.isEmpty =>
            "Both topicName and bootstrapServers have to be set".asLeft
          case other => other.asRight
        }
        .emap {
          case PubSub(_, p, _, _, _) if p <= 0 =>
            "PubSub parallelPullCount must be > 0".asLeft
          case PubSub(_, _, m, _, _) if m <= 0 =>
            "PubSub maxQueueSize must be > 0".asLeft
          case PubSub(_, _, _, m, _) if m <= 0 =>
            "PubSub maxRequestBytes must be > 0".asLeft
          case PubSub(_, _, _, _, m) if m < Duration.Zero =>
            "PubSub maxAckExtensionPeriod must be >= 0".asLeft
          case other =>
            other.asRight
        }
    implicit val inputEncoder: Encoder[Input] =
      deriveConfiguredEncoder[Input]
  }

  case class Outputs(
    good: Output,
    pii: Option[Output],
    bad: Output
  )
  object Outputs {
    implicit val outputsDecoder: Decoder[Outputs] = deriveConfiguredDecoder[Outputs]
    implicit val outputsEncoder: Encoder[Outputs] = deriveConfiguredEncoder[Outputs]
  }

  sealed trait Output

  object Output {

    case class Kafka private (
      topicName: String,
      bootstrapServers: String,
      partitionKey: String,
      headers: Set[String],
      producerConf: Map[String, String]
    ) extends Output

    case class Nsq private (
      topic: String,
      nsqdHost: String,
      nsqdPort: Int,
      backoffPolicy: BackoffPolicy
    ) extends Output

    case class PubSub private (
      topic: String,
      attributes: Option[Set[String]],
      delayThreshold: FiniteDuration,
      maxBatchSize: Long,
      maxBatchBytes: Long
    ) extends Output {
      val (project, name) =
        topic.split("/").toList match {
          case _ if topic.isEmpty =>
            ("", "")
          case List("projects", project, "topics", name) =>
            (project, name)
          case _ =>
            throw new IllegalArgumentException(s"Topic format $topic invalid")
        }
    }
    case class FileSystem(file: Path, maxBytes: Option[Long]) extends Output
    case class Kinesis(
      streamName: String,
      region: Option[String],
      partitionKey: Option[String],
      backoffPolicy: BackoffPolicy,
      throttledBackoffPolicy: BackoffPolicy,
      recordLimit: Int,
      byteLimit: Int,
      customEndpoint: Option[URI]
    ) extends Output
    case class Eventbridge(
      eventBusName: String,
      eventBusSource: String,
      region: Option[String],
      backoffPolicy: BackoffPolicy,
      throttledBackoffPolicy: BackoffPolicy,
      recordLimit: Int,
      byteLimit: Int,
      customEndpoint: Option[URI],
      collector: Option[Boolean],
      payload: Option[Boolean]
    ) extends Output
    case class RabbitMQ(
      cluster: RabbitMQConfig,
      exchange: String,
      routingKey: String,
      backoffPolicy: BackoffPolicy
    ) extends Output

    implicit val outputDecoder: Decoder[Output] =
      deriveConfiguredDecoder[Output]
        .emap {
          case Kafka(topicName, bootstrapServers, _, _, _) if topicName.isEmpty ^ bootstrapServers.isEmpty =>
            "Both topicName and bootstrapServers have to be set".asLeft
          case s @ PubSub(top, _, _, _, _) if top.nonEmpty =>
            top.split("/").toList match {
              case List("projects", _, "topics", _) =>
                s.asRight
              case _ =>
                s"Topic must conform projects/project-name/topics/topic-name format, $top given".asLeft
            }
          case k: Kinesis if k.streamName.isEmpty && k.region.nonEmpty =>
            "streamName needs to be set".asLeft
          case k: Eventbridge if k.eventBusName.isEmpty && k.region.nonEmpty =>
            "eventBusName needs to be set".asLeft
          case other => other.asRight
        }
        .emap {
          case Kafka(_, _, pk, _, _) if pk.nonEmpty && !ParsedConfigs.isValidPartitionKey(pk) =>
            s"Kafka partition key [$pk] is invalid".asLeft
          case ka: Kafka if ka.headers.nonEmpty =>
            val invalidAttrs = ParsedConfigs.filterInvalidAttributes(ka.headers)
            if (invalidAttrs.nonEmpty) s"Kafka headers [${invalidAttrs.mkString(",")}] are invalid".asLeft
            else ka.asRight
          case p: PubSub if p.delayThreshold < Duration.Zero =>
            "PubSub delay threshold cannot be less than 0".asLeft
          case p: PubSub if p.maxBatchSize < 0 =>
            "PubSub max batch size cannot be less than 0".asLeft
          case p: PubSub if p.maxBatchBytes < 0 =>
            "PubSub max batch bytes cannot be less than 0".asLeft
          case k: Kinesis if k.recordLimit > putRecordsMaxRecords =>
            s"recordLimit can't be > $putRecordsMaxRecords".asLeft
          case other =>
            other.asRight
        }

    implicit val outputEncoder: Encoder[Output] =
      deriveConfiguredEncoder[Output]
  }

  final case class Concurrency(enrich: Long, sink: Int)

  object Concurrency {
    implicit val concurrencyDecoder: Decoder[Concurrency] =
      deriveConfiguredDecoder[Concurrency]
    implicit val concurrencyEncoder: Encoder[Concurrency] =
      deriveConfiguredEncoder[Concurrency]
  }

  final case class MetricsReporters(
    statsd: Option[MetricsReporters.StatsD],
    stdout: Option[MetricsReporters.Stdout],
    cloudwatch: Boolean
  )

  object MetricsReporters {
    final case class Stdout(period: FiniteDuration, prefix: Option[String])
    final case class StatsD(
      hostname: String,
      port: Int,
      tags: Map[String, String],
      period: FiniteDuration,
      prefix: Option[String]
    )

    implicit val stdoutDecoder: Decoder[Stdout] =
      deriveConfiguredDecoder[Stdout].emap { stdout =>
        if (stdout.period < Duration.Zero)
          "metrics report period in config file cannot be less than 0".asLeft
        else
          stdout.asRight
      }

    implicit val statsDecoder: Decoder[StatsD] =
      deriveConfiguredDecoder[StatsD].emap { statsd =>
        if (statsd.period < Duration.Zero)
          "metrics report period in config file cannot be less than 0".asLeft
        else
          statsd.asRight
      }

    implicit val metricsReportersDecoder: Decoder[MetricsReporters] =
      deriveConfiguredDecoder[MetricsReporters]

    implicit val stdoutEncoder: Encoder[Stdout] =
      deriveConfiguredEncoder[Stdout]

    implicit val statsdEncoder: Encoder[StatsD] =
      deriveConfiguredEncoder[StatsD]

    implicit val metricsReportersEncoder: Encoder[MetricsReporters] =
      deriveConfiguredEncoder[MetricsReporters]

    def normalizeMetric(prefix: Option[String], metric: String): String =
      s"${prefix.getOrElse(DefaultPrefix).stripSuffix(".")}.$metric".stripPrefix(".")

    val DefaultPrefix = "snowplow.enrich"
  }

  case class Monitoring(sentry: Option[Sentry], metrics: MetricsReporters)

  object Monitoring {
    implicit val monitoringDecoder: Decoder[Monitoring] =
      deriveConfiguredDecoder[Monitoring]
    implicit val monitoringEncoder: Encoder[Monitoring] =
      deriveConfiguredEncoder[Monitoring]
  }

  case class RemoteAdapterConfig(
    vendor: String,
    version: String,
    url: String
  )

  object RemoteAdapterConfig {
    implicit val remoteAdapterConfigEncoder: Encoder[RemoteAdapterConfig] =
      deriveConfiguredEncoder[RemoteAdapterConfig]

    implicit val remoteAdapterConfigDecoder: Decoder[RemoteAdapterConfig] =
      deriveConfiguredDecoder[RemoteAdapterConfig]
  }

  case class RemoteAdapterConfigs(
    connectionTimeout: FiniteDuration,
    readTimeout: FiniteDuration,
    maxConnections: Int,
    configs: List[RemoteAdapterConfig]
  )

  object RemoteAdapterConfigs {
    implicit val remoteAdapterConfigsEncoder: Encoder[RemoteAdapterConfigs] =
      deriveConfiguredEncoder[RemoteAdapterConfigs]

    implicit val remoteAdapterConfigsDecoder: Decoder[RemoteAdapterConfigs] =
      deriveConfiguredDecoder[RemoteAdapterConfigs]
  }

  case class Telemetry(
    disable: Boolean,
    interval: FiniteDuration,
    method: String,
    collectorUri: String,
    collectorPort: Int,
    secure: Boolean,
    userProvidedId: Option[String],
    autoGeneratedId: Option[String],
    instanceId: Option[String],
    moduleName: Option[String],
    moduleVersion: Option[String]
  )

  object Telemetry {
    implicit val telemetryDecoder: Decoder[Telemetry] =
      deriveConfiguredDecoder[Telemetry]
    implicit val telemetryEncoder: Encoder[Telemetry] =
      deriveConfiguredEncoder[Telemetry]
  }

  case class Metadata(
    endpoint: Uri,
    interval: FiniteDuration,
    organizationId: UUID,
    pipelineId: UUID
  )
  object Metadata {
    implicit val metadataDecoder: Decoder[Metadata] =
      deriveConfiguredDecoder[Metadata]
    implicit val metadataEncoder: Encoder[Metadata] =
      deriveConfiguredEncoder[Metadata]
  }

  case class Experimental(metadata: Option[Metadata])
  object Experimental {
    implicit val experimentalDecoder: Decoder[Experimental] =
      deriveConfiguredDecoder[Experimental]
    implicit val experimentalEncoder: Encoder[Experimental] =
      deriveConfiguredEncoder[Experimental]
  }

  case class FeatureFlags(
    acceptInvalid: Boolean,
    legacyEnrichmentOrder: Boolean,
    tryBase64Decoding: Boolean
  )

  object FeatureFlags {
    implicit val featureFlagsDecoder: Decoder[FeatureFlags] =
      deriveConfiguredDecoder[FeatureFlags]
    implicit val featureFlagsEncoder: Encoder[FeatureFlags] =
      deriveConfiguredEncoder[FeatureFlags]

    // Currently the FS2 feature flags exactly match the common feature flags, but it might not always be like this.
    def toCommon(ff: FeatureFlags): CommonFeatureFlags =
      CommonFeatureFlags(
        acceptInvalid = ff.acceptInvalid,
        legacyEnrichmentOrder = ff.legacyEnrichmentOrder
      )
  }

  object AdaptersSchemasEncoderDecoders {
    implicit val adaptersSchemasDecoder: Decoder[AdaptersSchemas] =
      deriveConfiguredDecoder[AdaptersSchemas]
    implicit val adaptersSchemasEncoder: Encoder[AdaptersSchemas] =
      deriveConfiguredEncoder[AdaptersSchemas]
    implicit val callrailSchemasDecoder: Decoder[CallrailSchemas] =
      deriveConfiguredDecoder[CallrailSchemas]
    implicit val callrailSchemasEncoder: Encoder[CallrailSchemas] =
      deriveConfiguredEncoder[CallrailSchemas]
    implicit val cloudfrontAccessLogSchemasDecoder: Decoder[CloudfrontAccessLogSchemas] =
      deriveConfiguredDecoder[CloudfrontAccessLogSchemas]
    implicit val cloudfrontAccessLogSchemasEncoder: Encoder[CloudfrontAccessLogSchemas] =
      deriveConfiguredEncoder[CloudfrontAccessLogSchemas]
    implicit val googleAnalyticsSchemasDecoder: Decoder[GoogleAnalyticsSchemas] =
      deriveConfiguredDecoder[GoogleAnalyticsSchemas]
    implicit val googleAnalyticsSchemasEncoder: Encoder[GoogleAnalyticsSchemas] =
      deriveConfiguredEncoder[GoogleAnalyticsSchemas]
    implicit val hubspotSchemasDecoder: Decoder[HubspotSchemas] =
      deriveConfiguredDecoder[HubspotSchemas]
    implicit val hubspotSchemasEncoder: Encoder[HubspotSchemas] =
      deriveConfiguredEncoder[HubspotSchemas]
    implicit val mailchimpSchemasDecoder: Decoder[MailchimpSchemas] =
      deriveConfiguredDecoder[MailchimpSchemas]
    implicit val mailchimpSchemasEncoder: Encoder[MailchimpSchemas] =
      deriveConfiguredEncoder[MailchimpSchemas]
    implicit val mailgunSchemasDecoder: Decoder[MailgunSchemas] =
      deriveConfiguredDecoder[MailgunSchemas]
    implicit val mailgunSchemasEncoder: Encoder[MailgunSchemas] =
      deriveConfiguredEncoder[MailgunSchemas]
    implicit val mandrillSchemasDecoder: Decoder[MandrillSchemas] =
      deriveConfiguredDecoder[MandrillSchemas]
    implicit val mandrillSchemasEncoder: Encoder[MandrillSchemas] =
      deriveConfiguredEncoder[MandrillSchemas]
    implicit val marketoSchemasDecoder: Decoder[MarketoSchemas] =
      deriveConfiguredDecoder[MarketoSchemas]
    implicit val marketoSchemasEncoder: Encoder[MarketoSchemas] =
      deriveConfiguredEncoder[MarketoSchemas]
    implicit val olarkSchemasDecoder: Decoder[OlarkSchemas] =
      deriveConfiguredDecoder[OlarkSchemas]
    implicit val olarkSchemasEncoder: Encoder[OlarkSchemas] =
      deriveConfiguredEncoder[OlarkSchemas]
    implicit val pagerdutySchemasDecoder: Decoder[PagerdutySchemas] =
      deriveConfiguredDecoder[PagerdutySchemas]
    implicit val pagerdutySchemasEncoder: Encoder[PagerdutySchemas] =
      deriveConfiguredEncoder[PagerdutySchemas]
    implicit val pingdomSchemasDecoder: Decoder[PingdomSchemas] =
      deriveConfiguredDecoder[PingdomSchemas]
    implicit val pingdomSchemasEncoder: Encoder[PingdomSchemas] =
      deriveConfiguredEncoder[PingdomSchemas]
    implicit val sendgridSchemasDecoder: Decoder[SendgridSchemas] =
      deriveConfiguredDecoder[SendgridSchemas]
    implicit val sendgridSchemasEncoder: Encoder[SendgridSchemas] =
      deriveConfiguredEncoder[SendgridSchemas]
    implicit val statusgatorSchemasDecoder: Decoder[StatusGatorSchemas] =
      deriveConfiguredDecoder[StatusGatorSchemas]
    implicit val statusgatorSchemasEncoder: Encoder[StatusGatorSchemas] =
      deriveConfiguredEncoder[StatusGatorSchemas]
    implicit val unbounceSchemasDecoder: Decoder[UnbounceSchemas] =
      deriveConfiguredDecoder[UnbounceSchemas]
    implicit val unbounceSchemasEncoder: Encoder[UnbounceSchemas] =
      deriveConfiguredEncoder[UnbounceSchemas]
    implicit val urbanAirshipSchemasDecoder: Decoder[UrbanAirshipSchemas] =
      deriveConfiguredDecoder[UrbanAirshipSchemas]
    implicit val urbanAirshipSchemasEncoder: Encoder[UrbanAirshipSchemas] =
      deriveConfiguredEncoder[UrbanAirshipSchemas]
    implicit val veroSchemasDecoder: Decoder[VeroSchemas] =
      deriveConfiguredDecoder[VeroSchemas]
    implicit val veroSchemasEncoder: Encoder[VeroSchemas] =
      deriveConfiguredEncoder[VeroSchemas]
  }

  sealed trait Cloud

  object Cloud {
    case object Aws extends Cloud
    case object Gcp extends Cloud
    case object Azure extends Cloud

    implicit val encoder: Encoder[Cloud] = Encoder.encodeString.contramap[Cloud](_.toString().toUpperCase())
  }
}
