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
package com.snowplowanalytics.snowplow.enrich.nsq

import java.net.URI
import java.util.UUID
import java.nio.file.Paths

import scala.concurrent.duration._

import cats.syntax.either._
import cats.effect.IO

import org.http4s.Uri

import cats.effect.testing.specs2.CatsIO

import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.BackoffPolicy
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.{ConfigFile, Sentry}
import com.snowplowanalytics.snowplow.enrich.common.SpecHelpers.adaptersSchemas
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.BlobStorageClients.AzureStorage
import org.specs2.mutable.Specification

class ConfigSpec extends Specification with CatsIO {

  "parse" should {
    "parse reference example for NSQ" in {
      val configPath = Paths.get(getClass.getResource("/config.nsq.extended.hocon").toURI)
      val expected = ConfigFile(
        io.Input.Nsq(
          "collector-payloads",
          "collector-payloads-channel",
          "127.0.0.1",
          4161,
          3000,
          BackoffPolicy(
            minBackoff = 100.milliseconds,
            maxBackoff = 10.seconds,
            maxRetries = Some(10)
          )
        ),
        io.Outputs(
          io.Output.Nsq(
            "enriched",
            "127.0.0.1",
            4150,
            BackoffPolicy(
              minBackoff = 100.milliseconds,
              maxBackoff = 10.seconds,
              maxRetries = Some(10)
            )
          ),
          Some(
            io.Output.Nsq(
              "pii",
              "127.0.0.1",
              4150,
              BackoffPolicy(
                minBackoff = 100.milliseconds,
                maxBackoff = 10.seconds,
                maxRetries = Some(10)
              )
            )
          ),
          io.Output.Nsq(
            "bad",
            "127.0.0.1",
            4150,
            BackoffPolicy(
              minBackoff = 100.milliseconds,
              maxBackoff = 10.seconds,
              maxRetries = Some(10)
            )
          )
        ),
        io.Concurrency(256, 3),
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
            false
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
          Some("enrich-nsq-ce"),
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
                Uri.uri("https://my_pipeline.my_domain.com/iglu"),
                5.minutes,
                UUID.fromString("c5f3a09f-75f8-4309-bec5-fea560f78455"),
                UUID.fromString("75a13583-5c99-40e3-81fc-541084dfc784")
              )
            )
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
        )
      )
      ConfigFile.parse[IO](configPath.asRight).value.map(result => result must beRight(expected))
    }

    "parse minimal example for NSQ" in {
      val configPath = Paths.get(getClass.getResource("/config.nsq.minimal.hocon").toURI)
      val expected = ConfigFile(
        io.Input.Nsq(
          "collector-payloads",
          "collector-payloads-channel",
          "127.0.0.1",
          4161,
          3000,
          BackoffPolicy(
            minBackoff = 100.milliseconds,
            maxBackoff = 10.seconds,
            maxRetries = Some(10)
          )
        ),
        io.Outputs(
          io.Output.Nsq(
            "enriched",
            "127.0.0.1",
            4150,
            BackoffPolicy(
              minBackoff = 100.milliseconds,
              maxBackoff = 10.seconds,
              maxRetries = Some(10)
            )
          ),
          None,
          io.Output.Nsq(
            "bad",
            "127.0.0.1",
            4150,
            BackoffPolicy(
              minBackoff = 100.milliseconds,
              maxBackoff = 10.seconds,
              maxRetries = Some(10)
            )
          )
        ),
        io.Concurrency(256, 3),
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
            false
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
        io.BlobStorageClients(gcs = true, s3 = true, azureStorage = None)
      )
      ConfigFile.parse[IO](configPath.asRight).value.map(result => result must beRight(expected))
    }
  }
}
