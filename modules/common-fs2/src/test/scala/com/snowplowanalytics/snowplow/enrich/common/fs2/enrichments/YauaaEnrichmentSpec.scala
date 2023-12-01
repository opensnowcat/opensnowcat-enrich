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

import cats.implicits._

import cats.effect.testing.specs2.CatsIO

import fs2.Stream

import io.circe.literal._

import com.snowplowanalytics.iglu.core.{SchemaKey, SchemaVer, SelfDescribingData}

import com.snowplowanalytics.snowplow.analytics.scalasdk.SnowplowEvent.Contexts

import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.EnrichmentConf.YauaaConf

import com.snowplowanalytics.snowplow.enrich.common.fs2.EnrichSpec
import com.snowplowanalytics.snowplow.enrich.common.fs2.test._

import org.specs2.mutable.Specification

class YauaaEnrichmentSpec extends Specification with CatsIO {

  sequential

  "YauaaEnrichment" should {
    "add a derived context" in {
      val payload = EnrichSpec.collectorPayload.copy(
        context = EnrichSpec.collectorPayload.context.copy(
          useragent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.14; rv:81.0) Gecko/20100101 Firefox/81.0".some
        )
      )
      val input = Stream(payload.toRaw)

      /** Schemas defined at [[SchemaRegistry]] */
      val enrichment = YauaaConf(
        SchemaKey("com.acme", "enrichment", "jsonschema", SchemaVer.Full(1, 0, 0)),
        Some(1)
      )

      val expected = Contexts(
        List(
          SelfDescribingData(
            SchemaKey("nl.basjes", "yauaa_context", "jsonschema", SchemaVer.Full(1, 0, 4)),
            json"""{
          "deviceBrand" : "Apple",
          "deviceName" : "Apple Macintosh",
          "operatingSystemVersionMajor" : "10.14",
          "layoutEngineNameVersion" : "Gecko 81.0",
          "operatingSystemNameVersion" : "Mac OS 10.14",
          "agentInformationEmail": "Unknown",
          "networkType": "Unknown",
          "layoutEngineBuild" : "20100101",
          "webviewAppNameVersionMajor": "Unknown ??",
          "layoutEngineNameVersionMajor" : "Gecko 81",
          "operatingSystemName" : "Mac OS",
          "agentVersionMajor" : "81",
          "layoutEngineVersionMajor" : "81",
          "webviewAppName": "Unknown",
          "deviceClass" : "Desktop",
          "agentNameVersionMajor" : "Firefox 81",
          "operatingSystemNameVersionMajor" : "Mac OS 10.14",
          "deviceCpuBits" : "64",
          "webviewAppVersionMajor": "??",
          "operatingSystemClass" : "Desktop",
          "webviewAppVersion": "??",
          "layoutEngineName" : "Gecko",
          "agentName" : "Firefox",
          "agentVersion" : "81.0",
          "layoutEngineClass" : "Browser",
          "agentNameVersion" : "Firefox 81.0",
          "operatingSystemVersion" : "10.14",
          "deviceCpu" : "Intel",
          "agentClass" : "Browser",
          "layoutEngineVersion" : "81.0"
        }"""
          )
        )
      )

      TestEnvironment.make(input, List(enrichment)).use { test =>
        test.run().map {
          case (bad, pii, good) =>
            (bad must be empty)
            (pii must be empty)
            good.map(_.derived_contexts) must contain(exactly(expected))
        }
      }
    }
  }
}
