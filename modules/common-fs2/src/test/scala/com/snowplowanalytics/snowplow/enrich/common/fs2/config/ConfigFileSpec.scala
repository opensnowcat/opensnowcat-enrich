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

import java.net.URI
import java.util.UUID
import java.nio.file.Paths

import scala.concurrent.duration._

import cats.syntax.either._
import cats.data.NonEmptyList
import cats.effect.IO

import cats.effect.testing.specs2.CatsIO

import com.typesafe.config.ConfigFactory

import org.http4s.Uri

import org.specs2.mutable.Specification

import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.BackoffPolicy
import com.snowplowanalytics.snowplow.enrich.common.SpecHelpers.adaptersSchemas

class ConfigFileSpec extends Specification with CatsIO {
  "parse" should {
    "parse reference example for PubSub" in {
      val configPath = Paths.get(getClass.getResource("/config.pubsub.extended.hocon").toURI)
      val expected = ConfigFile(
        io.Input.PubSub("projects/test-project/subscriptions/collector-payloads-sub", 1, 3000, 50000000, 1.hour),
        io.Outputs(
          io.Output.PubSub("projects/test-project/topics/enriched", Some(Set("app_id")), 200.milliseconds, 1000, 8000000),
          Some(io.Output.PubSub("projects/test-project/topics/pii", None, 200.milliseconds, 1000, 8000000)),
          io.Output.PubSub("projects/test-project/topics/bad", None, 200.milliseconds, 1000, 8000000)
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
        adaptersSchemas
      )
      ConfigFile.parse[IO](configPath.asRight).value.map(result => result must beRight(expected))
    }

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
            None
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
              None
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
        adaptersSchemas
      )
      ConfigFile.parse[IO](configPath.asRight).value.map(result => result must beRight(expected))
    }

    "parse reference example for RabbitMQ" in {
      val configPath = Paths.get(getClass.getResource("/config.rabbitmq.extended.hocon").toURI)
      val expected = ConfigFile(
        io.Input.RabbitMQ(
          io.RabbitMQConfig(
            NonEmptyList.one(
              io.RabbitMQNode("localhost", 5672)
            ),
            "guest",
            "guest",
            "/",
            5,
            false,
            1000,
            100,
            true
          ),
          "raw",
          io.BackoffPolicy(100.millis, 10.seconds, Some(10))
        ),
        io.Outputs(
          io.Output.RabbitMQ(
            io.RabbitMQConfig(
              NonEmptyList.one(
                io.RabbitMQNode("localhost", 5672)
              ),
              "guest",
              "guest",
              "/",
              5,
              false,
              1000,
              100,
              true
            ),
            "enriched",
            "enriched",
            io.BackoffPolicy(100.millis, 10.seconds, Some(10))
          ),
          None,
          io.Output.RabbitMQ(
            io.RabbitMQConfig(
              NonEmptyList.one(
                io.RabbitMQNode("localhost", 5672)
              ),
              "guest",
              "guest",
              "/",
              5,
              false,
              1000,
              100,
              true
            ),
            "bad-1",
            "bad-1",
            io.BackoffPolicy(100.millis, 10.seconds, Some(10))
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
          Some("enrich-rabbitmq-ce"),
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
        adaptersSchemas
      )
      ConfigFile.parse[IO](configPath.asRight).value.map(result => result must beRight(expected))
    }

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
        adaptersSchemas
      )
      ConfigFile.parse[IO](configPath.asRight).value.map(result => result must beRight(expected))
    }

    "parse reference example for Eventbridge" in {
      val configPath = Paths.get(getClass.getResource("/config.eventbridge.extended.hocon").toURI)
      val expected = ConfigFile(
        io.Input.Kinesis(
          "snowplow-enrich-eventbridge",
          "input-stream-name",
          Some("us-west-2"),
          io.Input.Kinesis.InitPosition.Latest,
          io.Input.Kinesis.Retrieval.Polling(10000),
          3,
          io.BackoffPolicy(100.milli, 10.second, Some(10)),
          None,
          None,
          None
        ),
        io.Outputs(
          io.Output.Eventbridge(
            "output-good-eventbus-name",
            "output-good-eventbus-source",
            Some("us-west-2"),
            io.BackoffPolicy(100.millis, 10.seconds, Some(10)),
            io.BackoffPolicy(100.millis, 1.second, None),
            10,
            250000,
            None,
            Some(true),
            Some(true)
          ),
          Some(
            io.Output.Eventbridge(
              "output-pii-eventbus-name",
              "output-pii-eventbus-source",
              Some("us-west-2"),
              io.BackoffPolicy(100.millis, 10.seconds, Some(10)),
              io.BackoffPolicy(100.millis, 1.second, None),
              10,
              250000,
              None,
              Some(true),
              Some(true)
            )
          ),
          io.Output.Eventbridge(
            "output-bad-eventbus-name",
            "output-bad-eventbus-source",
            Some("us-west-2"),
            io.BackoffPolicy(100.millis, 10.seconds, Some(10)),
            io.BackoffPolicy(100.millis, 1.second, None),
            10,
            250000,
            None,
            None,
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
          None,
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
          Some("enrich-eventbridge-ce"),
          Some("1.0.0")
        ),
        io.FeatureFlags(
          false,
          false,
          tryBase64Decoding = true
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
        adaptersSchemas
      )
      ConfigFile.parse[IO](configPath.asRight).value.map(result => result must beRight(expected))
    }

    "parse valid 0 minutes as None" in {
      val input =
        """{
          "input": {
            "type": "PubSub",
            "subscription": "projects/test-project/subscriptions/inputSub",
            "parallelPullCount": 1,
            "maxQueueSize": 3000,
            "maxRequestBytes": 50000000,
            "maxAckExtensionPeriod": 1 hour
          },
          "output": {
            "good": {
              "type": "PubSub",
              "topic": "projects/test-project/topics/good-topic",
              "delayThreshold": "200 milliseconds",
              "maxBatchSize": 1000,
              "maxBatchBytes": 8000000
            },
            "pii": {
              "type": "PubSub",
              "topic": "projects/test-project/topics/pii-topic",
              "delayThreshold": "200 milliseconds",
              "maxBatchSize": 1000,
              "maxBatchBytes": 8000000
            },
            "bad": {
              "type": "PubSub",
              "topic": "projects/test-project/topics/bad-topic",
              "delayThreshold": "200 milliseconds",
              "maxBatchSize": 1000,
              "maxBatchBytes": 8000000
            }
          },
          "concurrency": {
            "enrich": 256,
            "sink": 3
          },
          "assetsUpdatePeriod": "0 minutes",
          "remoteAdapters": {
            "connectionTimeout": "10 seconds",
            "readTimeout": "45 seconds",
            "maxConnections": 10,
            "configs": []
          },
          "metricsReportPeriod": "10 second",
          "telemetry": {
            "disable": false,
            "interval": "15 minutes",
            "method": "POST",
            "collectorUri": "collector-g.snowplowanalytics.com",
            "collectorPort": "443",
            "secure": true
          },
          "featureFlags" : {
            "acceptInvalid": false,
            "legacyEnrichmentOrder": false,
            "tryBase64Decoding": false
          },
          "experimental": {
            "metadata": {
               "endpoint": "https://my_pipeline.my_domain.com/iglu",
               "interval": "15 minutes",
               "organizationId": "c5f3a09f-75f8-4309-bec5-fea560f78455",
               "pipelineId": "75a13583-5c99-40e3-81fc-541084dfc784"
            }
          }
        }"""

      ConfigFile.parse[IO](Base64Hocon(ConfigFactory.parseString(input)).asLeft).value.map {
        case Left(message) => message must contain("assetsUpdatePeriod in config file cannot be less than 0")
        case _ => ko("Decoding should have failed")
      }
    }

    "resolve parameter substitutions in hocon ${} syntax" in {
      @annotation.nowarn("msg=possible missing interpolator")
      val input =
        """{
          
          "testSubstitutions": {
            "a": "test-substituted-collector-uri"
            "b": 42
          }

          "telemetry": {
            #### SUBSTITUTED VALUES MUST GET PROPERLY RESOLVED: ####
            ##
            "collectorUri": ${testSubstitutions.a}
            "collectorPort": ${testSubstitutions.b}
            ##
            ########################################################
          }


          "input": {
            "type": "FileSystem"
            "dir": "/path/to/input"
          },
          "output": {
            "good": {
              "type": "FileSystem"
              "file": "/path/to/good"
            },
            "bad": {
              "type": "FileSystem"
              "file": "/path/to/bad"
            }
          },
          "concurrency": {
            "enrich": 256
            "sink": 3
          },
          "remoteAdapters": {
            "connectionTimeout": "10 seconds"
            "readTimeout": "45 seconds"
            "maxConnections": 10
            "configs": []
          },
          "telemetry": {
            "disable": false
            "interval": "15 minutes"
            "method": "POST"
            "secure": true
          },
          "featureFlags" : {
            "acceptInvalid": false,
            "legacyEnrichmentOrder": false,
            "tryBase64Decoding": false
          }
        }"""

      ConfigFile.parse[IO](Base64Hocon(ConfigFactory.parseString(input)).asLeft).value.map { result =>
        result must beRight.like {
          case configFile =>
            configFile.telemetry.collectorUri must_== "test-substituted-collector-uri"
            configFile.telemetry.collectorPort must_== 42
        }
      }
    }

    "not throw an exception if file not found" in {
      val configPath = Paths.get("does-not-exist")
      ConfigFile.parse[IO](configPath.asRight).value.map(result => result must beLeft)
    }
  }
}
