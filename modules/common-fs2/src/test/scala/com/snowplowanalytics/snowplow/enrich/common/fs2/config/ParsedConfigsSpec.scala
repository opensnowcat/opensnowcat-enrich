/*
 * Copyright (c) 2020-2021 Snowplow Analytics Ltd. All rights reserved.
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
import scala.concurrent.duration._

import cats.effect.IO

import cats.effect.testing.specs2.CatsIO

import org.http4s.Uri

import org.specs2.mutable.Specification

import com.typesafe.config.ConfigFactory

import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import com.snowplowanalytics.snowplow.enrich.common.SpecHelpers.adaptersSchemas

class ParsedConfigsSpec extends Specification with CatsIO {
  "validateConfig" should {
    "return accumulated invalid attributes in config file" in {

      val invalidAttr1 = "invalidAttr1"
      val invalidAttr2 = "invalidAttr2"

      val configFile = ConfigFile(
        io.Input.PubSub("projects/test-project/subscriptions/inputSub", 1, 3000, 50000000, 1.hour),
        io.Outputs(
          io.Output.PubSub("projects/test-project/topics/good-topic", Some(Set("app_id", invalidAttr1)), 200.milliseconds, 1000, 10000000),
          Some(
            io.Output.PubSub("projects/test-project/topics/pii-topic", Some(Set("app_id", invalidAttr2)), 200.milliseconds, 1000, 10000000)
          ),
          io.Output.PubSub("projects/test-project/topics/bad-topic", None, 200.milliseconds, 1000, 10000000)
        ),
        io.Concurrency(10000, 64),
        Some(7.days),
        io.RemoteAdapterConfigs(
          10.seconds,
          45.seconds,
          10,
          List()
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
                Uri.uri("https://collector-g.snowplowanalytics.com"),
                5.minutes,
                UUID.fromString("c5f3a09f-75f8-4309-bec5-fea560f78455"),
                UUID.fromString("75a13583-5c99-40e3-81fc-541084dfc784")
              )
            )
          )
        ),
        adaptersSchemas
      )

      ParsedConfigs.validateConfig[IO](configFile).value.map(result => result must beLeft)
    }
  }

  "outputAttributes" should {
    "fetch attribute values" in {
      val output = io.Output.PubSub("projects/test-project/topics/good-topic", Some(Set("app_id")), 200.milliseconds, 1000, 10000000)
      val ee = new EnrichedEvent()
      ee.app_id = "test_app"

      val result = ParsedConfigs.outputAttributes(output)(ee)

      result must haveSize(1)
      result must haveKey("app_id")
      result must haveValue("test_app")
    }
  }

  "attributesFromFields" should {
    "fetch attribute values" in {
      val ee = new EnrichedEvent()
      ee.app_id = "test_attributesFromFields"

      val result = ParsedConfigs.attributesFromFields(Set("app_id"))(ee)

      result must haveSize(1)
      result must haveKey("app_id")
      result must haveValue("test_attributesFromFields")
    }
  }

  "outputPartitionKey" should {
    "fetch partition key's value" in {
      val output = io.Output.Kafka("good-topic", "localhost:9092", "app_id", Set(), Map.empty)
      val ee = new EnrichedEvent()
      ee.app_id = "test_outputPartitionKey"

      val result = ParsedConfigs.outputPartitionKey(output)(ee)

      result must beEqualTo("test_outputPartitionKey")
    }
  }

  "partitionKeyFromFields" should {
    "fetch partition key's value" in {
      val ee = new EnrichedEvent()
      ee.app_id = "test_partitionKeyFromFields"

      val result = ParsedConfigs.partitionKeyFromFields("app_id")(ee)

      result must beEqualTo("test_partitionKeyFromFields")
    }
  }

  "parseHoconToJson" should {
    "resolve hocon-syntax substitutions" in {

      val hocon = """
      {
        "substitution": "xyz"
        "abc": ${substitution}
      }
      """

      val in = Base64Hocon(ConfigFactory.parseString(hocon))

      ParsedConfigs.parseHoconToJson[IO](Left(in)).value.map { result =>
        result must beRight.like {
          case json =>
            json.asObject must beSome.like {
              case jsonObj =>
                jsonObj("abc").flatMap(_.asString) must beSome("xyz")
            }
        }
      }
    }
  }
}
