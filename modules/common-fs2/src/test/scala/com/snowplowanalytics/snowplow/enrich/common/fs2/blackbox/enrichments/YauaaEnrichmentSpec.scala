/*
 * Copyright (c) 2022-2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.common.fs2.blackbox.enrichments

import io.circe.literal._

import org.specs2.mutable.Specification

import cats.effect.testing.specs2.CatsIO

import cats.syntax.option._

import com.snowplowanalytics.snowplow.enrich.common.fs2.blackbox.BlackBoxTesting

class YauaaEnrichmentSpec extends Specification with CatsIO {
  "enrichWith" should {
    "enrich with YauaaEnrichment" in {
      val input = BlackBoxTesting.buildCollectorPayload(
        path = "/i",
        querystring = "e=pp".some,
        userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/106.0.0.0 Safari/537.36".some,
        headers = List(
          """Sec-CH-UA: "Chromium";v="106", "Google Chrome";v="106", "Not;A=Brand";v="99"""",
          """Sec-CH-UA-Arch: "x86"""",
          """Sec-CH-UA-Bitness: "64"""",
          """Sec-CH-UA-Full-Version: "106.0.5249.119"""",
          """Sec-CH-UA-Full-Version-List: "Chromium";v="106.0.5249.119", "Google Chrome";v="106.0.5249.119", "Not;A=Brand";v="99.0.0.0"""",
          """Sec-CH-UA-Mobile: ?0""",
          """Sec-CH-UA-Model: """"",
          """Sec-CH-UA-Platform: "Linux"""",
          """Sec-CH-ua-Platform-version: "6.0.1"""",
          """Sec-CH-UA-WoW64: ?0"""
        )
      )
      val expected = Map(
        "event_vendor" -> "com.snowplowanalytics.snowplow",
        "event_name" -> "page_ping",
        "event_format" -> "jsonschema",
        "event_version" -> "1-0-0",
        "event" -> "page_ping",
        "derived_contexts" -> json"""{
          "schema":"iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1",
          "data":[{
            "schema":"iglu:nl.basjes/yauaa_context/jsonschema/1-0-4",
            "data": {
              "deviceBrand":"Unknown",
              "deviceName":"Linux Desktop",
              "operatingSystemVersionMajor":"6",
              "layoutEngineNameVersion":"Blink 106.0",
              "operatingSystemNameVersion":"Linux 6.0.1",
              "agentInformationEmail": "Unknown",
              "networkType": "Unknown",
              "operatingSystemVersionBuild":"??",
              "webviewAppNameVersionMajor": "Unknown ??",
              "layoutEngineNameVersionMajor":"Blink 106",
              "operatingSystemName":"Linux",
              "agentVersionMajor":"106",
              "layoutEngineVersionMajor":"106",
              "webviewAppName": "Unknown",
              "deviceClass":"Desktop",
              "agentNameVersionMajor":"Chrome 106",
              "operatingSystemNameVersionMajor":"Linux 6",
              "deviceCpuBits":"64",
              "webviewAppVersionMajor": "??",
              "operatingSystemClass":"Desktop",
              "webviewAppVersion": "??",
              "layoutEngineName":"Blink",
              "agentName":"Chrome",
              "agentVersion":"106.0.5249.119",
              "layoutEngineClass":"Browser",
              "agentNameVersion":"Chrome 106.0.5249.119",
              "operatingSystemVersion":"6.0.1",
              "deviceCpu":"Intel x86_64",
              "agentClass":"Browser",
              "layoutEngineVersion":"106.0",
              "agentInformationUrl": "Unknown"
            }
          }]
        }""".noSpaces
      )
      BlackBoxTesting.runTest(input, expected, Some(YauaaEnrichmentSpec.conf))
    }
  }
}

object YauaaEnrichmentSpec {
  val conf = json"""
    {
      "schema": "iglu:com.snowplowanalytics.snowplow.enrichments/yauaa_enrichment_config/jsonschema/1-0-0",
      "data": {
        "enabled": true,
        "vendor": "com.snowplowanalytics.snowplow.enrichments",
        "name": "yauaa_enrichment_config"
      }
    }
  """
}
