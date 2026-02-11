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

import cats.effect.testing.specs2.CatsEffect

import cats.syntax.option._

import com.snowplowanalytics.snowplow.enrich.common.fs2.blackbox.BlackBoxTesting

class IabEnrichmentSpec extends Specification with CatsEffect {

  sequential

  "enrichWith" should {
    "enrich with IabEnrichment 1" in {
      val input = BlackBoxTesting.buildCollectorPayload(
        path = "/i",
        querystring = "e=pp".some,
        userAgent = "Mozilla/5.0%20(Windows%20NT%206.1;%20WOW64;%20rv:12.0)%20Gecko/20100101%20Firefox/12.0".some,
        ipAddress = "216.160.83.56"
      )
      val expected = Map(
        "event_vendor" -> "com.snowplowanalytics.snowplow",
        "event_name" -> "page_ping",
        "event_format" -> "jsonschema",
        "event_version" -> "1-0-0",
        "event" -> "page_ping",
        "derived_contexts" -> json"""{"schema":"iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1","data":[{"schema":"iglu:com.iab.snowplow/spiders_and_robots/jsonschema/1-0-0","data":{"spiderOrRobot":false,"category":"BROWSER","reason":"PASSED_ALL","primaryImpact":"NONE"}}]}""".noSpaces
      )
      BlackBoxTesting.runTest(input, expected, Some(IabEnrichmentSpec.conf))
    }

    "enrich with IabEnrichment 2" in {
      val input = BlackBoxTesting.buildCollectorPayload(
        path = "/i",
        querystring = "e=pp".some,
        userAgent =
          "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; Googlebot/2.1; +http://www.google.com/bot.html) Safari/537.36".some,
        ipAddress = "216.160.83.56"
      )
      val expected = Map(
        "event_vendor" -> "com.snowplowanalytics.snowplow",
        "event_name" -> "page_ping",
        "event_format" -> "jsonschema",
        "event_version" -> "1-0-0",
        "event" -> "page_ping",
        "derived_contexts" -> json"""{"schema":"iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1","data":[{"schema":"iglu:com.iab.snowplow/spiders_and_robots/jsonschema/1-0-0","data":{"spiderOrRobot":true,"category":"SPIDER_OR_ROBOT","reason":"FAILED_UA_INCLUDE","primaryImpact":"UNKNOWN"}}]}""".noSpaces
      )
      BlackBoxTesting.runTest(input, expected, Some(IabEnrichmentSpec.conf))
    }

    "enrich with IabEnrichment 3" in {
      val input = BlackBoxTesting.buildCollectorPayload(
        path = "/i",
        querystring = "e=pp".some,
        userAgent =
          "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; Googlebot/2.1; +http://www.google.com/bot.html) Safari/537.36".some,
        ipAddress = "216.160.83.56:8080"
      )
      val expected = Map(
        "event_vendor" -> "com.snowplowanalytics.snowplow",
        "event_name" -> "page_ping",
        "event_format" -> "jsonschema",
        "event_version" -> "1-0-0",
        "event" -> "page_ping",
        "derived_contexts" -> json"""{"schema":"iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1","data":[{"schema":"iglu:com.iab.snowplow/spiders_and_robots/jsonschema/1-0-0","data":{"spiderOrRobot":true,"category":"SPIDER_OR_ROBOT","reason":"FAILED_UA_INCLUDE","primaryImpact":"UNKNOWN"}}]}""".noSpaces
      )
      BlackBoxTesting.runTest(input, expected, Some(IabEnrichmentSpec.conf))
    }

    "enrich with IabEnrichment 4" in {
      val input = BlackBoxTesting.buildCollectorPayload(
        path = "/i",
        querystring = "e=pp".some,
        userAgent =
          "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; Googlebot/2.1; +http://www.google.com/bot.html) Safari/537.36".some,
        ipAddress = "2001:db8:0:0:0:ff00:42:8329"
      )
      val expected = Map(
        "event_vendor" -> "com.snowplowanalytics.snowplow",
        "event_name" -> "page_ping",
        "event_format" -> "jsonschema",
        "event_version" -> "1-0-0",
        "event" -> "page_ping",
        "derived_contexts" -> json"""{"schema":"iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1","data":[{"schema":"iglu:com.iab.snowplow/spiders_and_robots/jsonschema/1-0-0","data":{"spiderOrRobot":true,"category":"SPIDER_OR_ROBOT","reason":"FAILED_UA_INCLUDE","primaryImpact":"UNKNOWN"}}]}""".noSpaces
      )
      BlackBoxTesting.runTest(input, expected, Some(IabEnrichmentSpec.conf))
    }

    "enrich with IabEnrichment 5" in {
      val input = BlackBoxTesting.buildCollectorPayload(
        path = "/i",
        querystring = "e=pp".some,
        userAgent =
          "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; Googlebot/2.1; +http://www.google.com/bot.html) Safari/537.36".some,
        ipAddress = "[2001:db8:0:0:0:ff00:42:8329]:9090"
      )
      val expected = Map(
        "event_vendor" -> "com.snowplowanalytics.snowplow",
        "event_name" -> "page_ping",
        "event_format" -> "jsonschema",
        "event_version" -> "1-0-0",
        "event" -> "page_ping",
        "derived_contexts" -> json"""{"schema":"iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1","data":[{"schema":"iglu:com.iab.snowplow/spiders_and_robots/jsonschema/1-0-0","data":{"spiderOrRobot":true,"category":"SPIDER_OR_ROBOT","reason":"FAILED_UA_INCLUDE","primaryImpact":"UNKNOWN"}}]}""".noSpaces
      )
      BlackBoxTesting.runTest(input, expected, Some(IabEnrichmentSpec.conf))
    }

    "enrich with IabEnrichment 6" in {
      val input = BlackBoxTesting.buildCollectorPayload(
        path = "/i",
        querystring = "e=pp".some,
        ipAddress = "216.160.83.56"
      )
      val expected = Map(
        "event_vendor" -> "com.snowplowanalytics.snowplow",
        "event_name" -> "page_ping",
        "event_format" -> "jsonschema",
        "event_version" -> "1-0-0",
        "event" -> "page_ping",
        "derived_contexts" -> ""
      )
      BlackBoxTesting.runTest(input, expected, Some(IabEnrichmentSpec.conf))
    }
  }
}

object IabEnrichmentSpec {
  val conf = json"""
    {
      "schema": "iglu:com.snowplowanalytics.snowplow.enrichments/iab_spiders_and_robots_enrichment/jsonschema/1-0-0",
      "data": {
        "name": "iab_spiders_and_robots_enrichment",
        "vendor": "com.snowplowanalytics.snowplow.enrichments",
        "enabled": true,
        "parameters": {
          "ipFile": {
            "database": "ip_exclude_current_cidr.txt",
            "uri": "http://snowplow-hosted-assets.s3.amazonaws.com/third-party/iab"
          },
          "excludeUseragentFile": {
            "database": "exclude_current.txt",
            "uri": "http://snowplow-hosted-assets.s3.amazonaws.com/third-party/iab"
          },
          "includeUseragentFile": {
            "database": "include_current.txt",
            "uri": "http://snowplow-hosted-assets.s3.amazonaws.com/third-party/iab"
          }
        }
      }
    }
  """
}
