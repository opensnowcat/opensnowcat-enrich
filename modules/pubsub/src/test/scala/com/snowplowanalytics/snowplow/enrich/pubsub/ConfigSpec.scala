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
package com.snowplowanalytics.snowplow.enrich.pubsub

import java.net.URI
import java.util.UUID
import java.nio.file.Paths

import scala.concurrent.duration._

import cats.syntax.either._
import cats.effect.IO

import org.http4s.Uri

import cats.effect.testing.specs2.CatsIO

import com.snowplowanalytics.snowplow.enrich.common.fs2.config._

import com.snowplowanalytics.snowplow.enrich.common.SpecHelpers.adaptersSchemas

import org.specs2.mutable.Specification

class ConfigSpec extends Specification with CatsIO {

  "parse" should {
    "parse reference example for PubSub" in {
      val configPath = Paths.get(getClass.getResource("/config.pubsub.extended.hocon").toURI)
      val expected = ConfigFile(
        io.Input.PubSub(
          "projects/test-project/subscriptions/collector-payloads-sub",
          1,
          3000,
          50000000,
          1.hour,
          io.GcpUserAgent("Snowplow OSS")
        ),
        io.Outputs(
          io.Output.PubSub(
            "projects/test-project/topics/enriched",
            Some(Set("app_id")),
            200.milliseconds,
            1000,
            8000000,
            io.GcpUserAgent("Snowplow OSS")
          ),
          Some(
            io.Output.PubSub(
              "projects/test-project/topics/pii",
              None,
              200.milliseconds,
              1000,
              8000000,
              io.GcpUserAgent("Snowplow OSS")
            )
          ),
          io.Output.PubSub(
            "projects/test-project/topics/bad",
            None,
            200.milliseconds,
            1000,
            8000000,
            io.GcpUserAgent("Snowplow OSS")
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
          Some("enrich-kinesis-ce"),
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
            ),
            None
          )
        ),
        adaptersSchemas,
        io.BlobStorageClients(gcs = true, s3 = false, azureStorage = None)
      )
      ConfigFile.parse[IO](configPath.asRight).value.map(result => result must beRight(expected))
    }

    "parse minimal example for PubSub" in {
      val configPath = Paths.get(getClass.getResource("/config.pubsub.minimal.hocon").toURI)
      val expected = ConfigFile(
        io.Input.PubSub(
          "projects/test-project/subscriptions/collector-payloads-sub",
          1,
          3000,
          50000000,
          1.hour,
          io.GcpUserAgent("Snowplow OSS")
        ),
        io.Outputs(
          io.Output.PubSub(
            "projects/test-project/topics/enriched",
            None,
            200.milliseconds,
            1000,
            8000000,
            io.GcpUserAgent("Snowplow OSS")
          ),
          None,
          io.Output.PubSub(
            "projects/test-project/topics/bad",
            None,
            200.milliseconds,
            1000,
            8000000,
            io.GcpUserAgent("Snowplow OSS")
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
        io.BlobStorageClients(gcs = true, s3 = false, azureStorage = None)
      )
      ConfigFile.parse[IO](configPath.asRight).value.map(result => result must beRight(expected))
    }
  }
}
