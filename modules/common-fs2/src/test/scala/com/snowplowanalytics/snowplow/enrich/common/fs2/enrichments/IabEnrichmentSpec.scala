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
package com.snowplowanalytics.snowplow.enrich.common.fs2.enrichments

import java.net.URI

import scala.concurrent.duration._

import cats.syntax.apply._
import cats.syntax.option._

import cats.effect.testing.specs2.CatsIO

import io.circe.literal._

import fs2.Stream

import org.specs2.mutable.Specification

import com.snowplowanalytics.iglu.core.{SchemaKey, SchemaVer, SelfDescribingData}

import com.snowplowanalytics.snowplow.analytics.scalasdk.SnowplowEvent.Contexts

import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.EnrichmentConf

import com.snowplowanalytics.snowplow.enrich.common.fs2.EnrichSpec
import com.snowplowanalytics.snowplow.enrich.common.fs2.test.{HttpServer, TestEnvironment}

class IabEnrichmentSpec extends Specification with CatsIO {

  sequential

  "IabEnrichment" should {
    "recognize a robot by IP address" in {
      val payload = EnrichSpec.collectorPayload.copy(
        context = EnrichSpec.collectorPayload.context.copy(
          useragent = "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:12.0) Gecko/20100101 Firefox/12.0".some
        )
      )
      val input = Stream(payload.toRaw)
      val expected = Contexts(
        List(
          SelfDescribingData(
            SchemaKey("com.iab.snowplow", "spiders_and_robots", "jsonschema", SchemaVer.Full(1, 0, 0)),
            json"""{"spiderOrRobot":true,"category":"SPIDER_OR_ROBOT","reason":"FAILED_IP_EXCLUDE","primaryImpact":"UNKNOWN"}"""
          )
        )
      )
      val testWithHttp = HttpServer.resource *> TestEnvironment.make(input, List(IabEnrichmentSpec.enrichmentConf))
      testWithHttp.use { test =>
        test.run().map {
          case (bad, pii, good) =>
            (bad must be empty)
            (pii must be empty)
            good.map(_.derived_contexts) must contain(exactly(expected))
        }
      }
    }

    "refresh assets" in {
      val payload = EnrichSpec.collectorPayload.copy(
        context = EnrichSpec.collectorPayload.context.copy(
          useragent = "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:12.0) Gecko/20100101 Firefox/12.0".some
        )
      )
      val input = Stream(payload.toRaw) ++ Stream.sleep_(2.seconds) ++ Stream(payload.toRaw)

      val expectedOne = Contexts(
        List(
          SelfDescribingData(
            SchemaKey("com.iab.snowplow", "spiders_and_robots", "jsonschema", SchemaVer.Full(1, 0, 0)),
            json"""{"spiderOrRobot":true,"category":"SPIDER_OR_ROBOT","reason":"FAILED_IP_EXCLUDE","primaryImpact":"UNKNOWN"}"""
          )
        )
      )
      val expectedTwo = Contexts(
        List(
          SelfDescribingData(
            SchemaKey("com.iab.snowplow", "spiders_and_robots", "jsonschema", SchemaVer.Full(1, 0, 0)),
            json"""{"spiderOrRobot":false,"category":"BROWSER","reason":"PASSED_ALL","primaryImpact":"NONE"}"""
          )
        )
      )

      val testWithHttp = HttpServer.resource *> TestEnvironment.make(input, List(IabEnrichmentSpec.enrichmentConf))
      testWithHttp.use { test =>
        test.run(_.copy(assetsUpdatePeriod = Some(1800.millis))).map {
          case (bad, pii, good) =>
            (bad must be empty)
            (pii must be empty)
            good.map(_.derived_contexts) must contain(exactly(expectedOne, expectedTwo))
        }
      }
    }
  }
}

object IabEnrichmentSpec {
  val enrichmentConf = EnrichmentConf.IabConf(
    SchemaKey("com.acme", "enrichment", "jsonschema", SchemaVer.Full(1, 0, 0)),
    (URI.create("http://localhost:8080/iab/ip"), "ip"),
    (URI.create("http://localhost:8080/iab/exclude"), "exclude"),
    (URI.create("http://localhost:8080/iab/include"), "include")
  )
}
