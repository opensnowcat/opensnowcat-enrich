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

import scala.concurrent.duration.FiniteDuration

import cats.implicits._
import cats.data.EitherT
import cats.effect.Sync

import _root_.io.circe.{Decoder, Encoder}
import _root_.io.circe.config.syntax._
import _root_.io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}

import com.typesafe.config.{ConfigFactory, Config => TSConfig}

import com.snowplowanalytics.snowplow.enrich.common.adapters.AdaptersSchemas
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io._

/**
 * Parsed HOCON configuration file
 *
 * @param input input (PubSub, Kinesis etc)
 * @param output wraps good bad and pii outputs (PubSub, Kinesis, FS etc)
 * @param assetsUpdatePeriod time after which assets should be updated, in minutes
 * @param monitoring configuration for sentry and metrics
 * @param telemetry configuration for telemetry
 * @param featureFlags to activate/deactivate enrich features
 * @param experimental configuration for experimental features
 * @param adaptersSchemas configuration for adapters
 */
final case class ConfigFile(
  input: Input,
  output: Outputs,
  concurrency: Concurrency,
  assetsUpdatePeriod: Option[FiniteDuration],
  remoteAdapters: RemoteAdapterConfigs,
  monitoring: Monitoring,
  telemetry: Telemetry,
  featureFlags: FeatureFlags,
  experimental: Option[Experimental],
  adaptersSchemas: AdaptersSchemas
)

object ConfigFile {
  import AdaptersSchemasEncoderDecoders._

  // Missing in circe-config
  implicit val finiteDurationEncoder: Encoder[FiniteDuration] =
    implicitly[Encoder[String]].contramap(_.toString)

  implicit val configFileDecoder: Decoder[ConfigFile] =
    deriveConfiguredDecoder[ConfigFile].emap {
      case ConfigFile(_, _, _, Some(aup), _, _, _, _, _, _) if aup._1 <= 0L =>
        "assetsUpdatePeriod in config file cannot be less than 0".asLeft // TODO: use newtype
      // Remove pii output if streamName and region empty
      case c @ ConfigFile(_, Outputs(good, Some(output: Output.Kinesis), bad), _, _, _, _, _, _, _, _) if output.streamName.isEmpty =>
        c.copy(output = Outputs(good, None, bad)).asRight
      // Remove pii output if topic empty
      case c @ ConfigFile(_, Outputs(good, Some(Output.PubSub(t, _, _, _, _)), bad), _, _, _, _, _, _, _, _) if t.isEmpty =>
        c.copy(output = Outputs(good, None, bad)).asRight
      // Remove pii output if topic empty
      case c @ ConfigFile(_, Outputs(good, Some(Output.Kafka(topicName, _, _, _, _)), bad), _, _, _, _, _, _, _, _) if topicName.isEmpty =>
        c.copy(output = Outputs(good, None, bad)).asRight

      // Remove pii output if eventBusName is empty
      case c @ ConfigFile(_, Outputs(good, Some(output: Output.Eventbridge), bad), _, _, _, _, _, _, _, _) if output.eventBusName.isEmpty =>
        c.copy(output = Outputs(good, None, bad)).asRight

      case other => other.asRight
    }
  implicit val configFileEncoder: Encoder[ConfigFile] =
    deriveConfiguredEncoder[ConfigFile]

  /* Defines where to look for default values if they are not in the provided file
   *
   * Command line parameters have the highest priority.
   * Then the provided file.
   * Then the application.conf file.
   * Then the reference.conf file.
   */
  private def configFileFallbacks(in: TSConfig): TSConfig = {
    val defaultConf = ConfigFactory.load("reference.conf")
    ConfigFactory.load(in.withFallback(ConfigFactory.load().withFallback(defaultConf)))
  }

  def parse[F[_]: Sync](raw: EncodedHoconOrPath): EitherT[F, String, ConfigFile] =
    ParsedConfigs.parseEncodedOrPath(raw, configFileFallbacks)

}
