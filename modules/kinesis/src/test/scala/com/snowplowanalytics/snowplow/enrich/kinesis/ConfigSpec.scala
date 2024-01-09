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
package com.snowplowanalytics.snowplow.enrich.kinesis

import java.net.URI
import java.util.UUID
import java.nio.file.Paths

import scala.concurrent.duration._

import cats.syntax.either._
import cats.effect.IO

import cats.effect.testing.specs2.CatsIO

import org.http4s.Uri

import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.{ConfigFile, Sentry}
import com.snowplowanalytics.snowplow.enrich.common.SpecHelpers.adaptersSchemas

import org.specs2.mutable.Specification

class ConfigSpec extends Specification with CatsIO {

  "parse" should {
    "parse reference example for Kinesis" in {
      val configPath = Paths.get(getClass.getResource("/config.kinesis.extended.hocon").toURI)
      val expected = ConfigFile(
        io.Input.Kinesis(
          "snowplow-enrich-kinesis",
          "collector-payloads",
          Some("eu-central-1"),
          io.Input.Kinesis.InitPosition.TrimHorizon,
          io.Input.Kinesis.Retrieval.Polling(10000),
          3,
          io.BackoffPolicy(100.milli, 10.second, Some(10)),
          None,
          None,
          None
        ),
        io.Outputs(
          io.Output.Kinesis(
            "enriched",
            Some("eu-central-1"),
            None,
            io.BackoffPolicy(100.millis, 10.seconds, Some(10)),
            io.BackoffPolicy(100.millis, 1.second, None),
            500,
            5242880,
            None,
            jsonOutput = false
          ),
          Some(
            io.Output.Kinesis(
              "pii",
              Some("eu-central-1"),
              None,
              io.BackoffPolicy(100.millis, 10.seconds, Some(10)),
              io.BackoffPolicy(100.millis, 1.second, None),
              500,
              5242880,
              None,
              jsonOutput = false
            )
          ),
          io.Output.Kinesis(
            "bad",
            Some("eu-central-1"),
            None,
            io.BackoffPolicy(100.millis, 10.seconds, Some(10)),
            io.BackoffPolicy(100.millis, 1.second, None),
            500,
            5242880,
            None,
            jsonOutput = false
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
          "collector-g.snowplowanalytics.com",
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
            )
          )
        ),
        adaptersSchemas,
        io.BlobStorageClients(gcs = false, s3 = true, azureStorage = None)
      )
      ConfigFile.parse[IO](configPath.asRight).value.map(result => result must beRight(expected))
    }

    "parse minimal example for Kinesis" in {
      val configPath = Paths.get(getClass.getResource("/config.kinesis.minimal.hocon").toURI)
      val expected = ConfigFile(
        io.Input.Kinesis(
          "snowplow-enrich-kinesis",
          "collector-payloads",
          None,
          io.Input.Kinesis.InitPosition.TrimHorizon,
          io.Input.Kinesis.Retrieval.Polling(10000),
          3,
          io.BackoffPolicy(100.milli, 10.second, Some(10)),
          None,
          None,
          None
        ),
        io.Outputs(
          io.Output.Kinesis(
            "enriched",
            None,
            None,
            io.BackoffPolicy(100.millis, 10.seconds, Some(10)),
            io.BackoffPolicy(100.millis, 1.second, None),
            500,
            5242880,
            None,
            jsonOutput = false
          ),
          None,
          io.Output.Kinesis(
            "bad",
            None,
            None,
            io.BackoffPolicy(100.millis, 10.seconds, Some(10)),
            io.BackoffPolicy(100.millis, 1.second, None),
            500,
            5242880,
            None,
            jsonOutput = false
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
          "collector-g.snowplowanalytics.com",
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
        io.BlobStorageClients(gcs = false, s3 = true, azureStorage = None)
      )
      ConfigFile.parse[IO](configPath.asRight).value.map(result => result must beRight(expected))
    }
  }
}
