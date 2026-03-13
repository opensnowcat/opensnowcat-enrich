/*
 * Copyright (c) 2019-2023 Snowplow Analytics Ltd. All rights reserved.
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

import java.net.URI
import java.util.UUID
import java.nio.file.Paths

import scala.concurrent.duration._

import org.specs2.mutable.Specification

import cats.syntax.either._
import cats.effect.IO

import org.http4s.Uri

import cats.effect.testing.specs2.CatsEffect

import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.BlobStorageClients.AzureStorage
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.{ConfigFile, Sentry}

import com.snowplowanalytics.snowplow.enrich.common.SpecHelpers.adaptersSchemas

class ConfigSpec extends Specification with CatsEffect {

  "parse" should {
    "parse reference example for Kafka" in {
      val configPath = Paths.get(getClass.getResource("/config.kafka.extended.hocon").toURI)
      val expected = ConfigFile(
        io.Input.Kafka(
          "collector-payloads",
          "localhost:9092",
          Map(
            "auto.offset.reset" -> "earliest",
            "session.timeout.ms" -> "45000",
            "enable.auto.commit" -> "false",
            "group.id" -> "enrich"
          )
        ),
        io.Outputs(
          io.Output.Kafka(
            "enriched",
            "localhost:9092",
            "app_id",
            Set("app_id"),
            Map("acks" -> "all"),
            None
          ),
          Some(
            io.Output.Kafka(
              "pii",
              "localhost:9092",
              "app_id",
              Set("app_id"),
              Map("acks" -> "all"),
              None
            )
          ),
          io.Output.Kafka(
            "bad",
            "localhost:9092",
            "",
            Set(),
            Map("acks" -> "all"),
            None
          )
        ),
        io.Concurrency(256, 1),
        Some(7.days),
        io.RemoteAdapterConfigs(
          10.seconds,
          45.seconds,
          10,
          List(
            io.RemoteAdapterConfig("com.example", "v1", "https://remote-adapter.com")
          )
        ),
        io.Monitoring(
          Some(Sentry(URI.create("http://sentry.acme.com"))),
          io.MetricsReporters(
            Some(io.MetricsReporters.StatsD("localhost", 8125, Map("app" -> "enrich"), 10.seconds, None)),
            Some(io.MetricsReporters.Stdout(10.seconds, None)),
            true
          )
        ),
        io.Telemetry(
          false,
          15.minutes,
          "POST",
          "sp.snowcatcloud.com",
          443,
          true,
          Some("my_pipeline"),
          Some("hfy67e5ydhtrd"),
          Some("665bhft5u6udjf"),
          Some("enrich-kafka-ce"),
          Some("1.0.0")
        ),
        io.FeatureFlags(
          false,
          false,
          false
        ),
        Some(
          io.Experimental(
            Some(
              io.Metadata(
                Uri.unsafeFromString("https://my_pipeline.my_domain.com/iglu"),
                5.minutes,
                UUID.fromString("c5f3a09f-75f8-4309-bec5-fea560f78455"),
                UUID.fromString("75a13583-5c99-40e3-81fc-541084dfc784")
              )
            ),
            None
          )
        ),
        adaptersSchemas,
        io.BlobStorageClients(
          gcs = true,
          s3 = true,
          azureStorage = Some(
            io.BlobStorageClients.AzureStorage(
              List(
                AzureStorage.Account(name = "storageAccount1", auth = None),
                AzureStorage.Account(name = "storageAccount2", auth = Some(AzureStorage.Account.Auth.DefaultCredentialsChain)),
                AzureStorage.Account(name = "storageAccount3", auth = Some(AzureStorage.Account.Auth.SasToken("tokenValue")))
              )
            )
          )
        ),
        maxJsonDepth = 40
      )
      ConfigFile.parse[IO](configPath.asRight).value.map(result => result must beRight(expected))
    }

    "parse minimal example for Kafka" in {
      val configPath = Paths.get(getClass.getResource("/config.kafka.minimal.hocon").toURI)
      val expected = ConfigFile(
        io.Input.Kafka(
          "collector-payloads",
          "localhost:9092",
          Map(
            "auto.offset.reset" -> "earliest",
            "enable.auto.commit" -> "false",
            "group.id" -> "enrich"
          )
        ),
        io.Outputs(
          io.Output.Kafka(
            "enriched",
            "localhost:9092",
            "",
            Set(),
            Map("acks" -> "all"),
            None
          ),
          None,
          io.Output.Kafka(
            "bad",
            "localhost:9092",
            "",
            Set(),
            Map("acks" -> "all"),
            None
          )
        ),
        io.Concurrency(256, 1),
        None,
        io.RemoteAdapterConfigs(
          10.seconds,
          45.seconds,
          10,
          List()
        ),
        io.Monitoring(
          None,
          io.MetricsReporters(
            None,
            None,
            true
          )
        ),
        io.Telemetry(
          false,
          15.minutes,
          "POST",
          "sp.snowcatcloud.com",
          443,
          true,
          None,
          None,
          None,
          None,
          None
        ),
        io.FeatureFlags(
          false,
          false,
          false
        ),
        None,
        adaptersSchemas,
        io.BlobStorageClients(gcs = false, s3 = false, azureStorage = None),
        maxJsonDepth = 40
      )
      ConfigFile.parse[IO](configPath.asRight).value.map(result => result must beRight(expected))
    }
  }

}
