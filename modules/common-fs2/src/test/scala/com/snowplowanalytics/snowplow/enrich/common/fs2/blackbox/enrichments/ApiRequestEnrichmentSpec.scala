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

class ApiRequestEnrichmentSpec extends Specification with CatsEffect {

  args(skipAll = !sys.env.get("CI").contains("true"))

  "enrichWith" should {
    "enrich with ApiRequestEnrichment" in {
      val contexts =
        """eyJkYXRhIjpbeyJkYXRhIjp7Im9zVHlwZSI6Ik9TWCIsImFwcGxlSWRmYSI6InNvbWVfYXBwbGVJZGZhIiwib3BlbklkZmEiOiJzb21lX0lkZmEiLCJjYXJyaWVyIjoic29tZV9jYXJyaWVyIiwiZGV2aWNlTW9kZWwiOiJsYXJnZSIsIm9zVmVyc2lvbiI6IjMuMC4wIiwiYXBwbGVJZGZhIjoic29tZV9hcHBsZUlkZmEiLCJhbmRyb2lkSWRmYSI6InNvbWVfYW5kcm9pZElkZmEiLCJkZXZpY2VNYW51ZmFjdHVyZXIiOiJBbXN0cmFkIn0sInNjaGVtYSI6ImlnbHU6Y29tLnNub3dwbG93YW5hbHl0aWNzLnNub3dwbG93L21vYmlsZV9jb250ZXh0L2pzb25zY2hlbWEvMS0wLTAifSx7ImRhdGEiOnsibG9uZ2l0dWRlIjoxMCwiYmVhcmluZyI6NTAsInNwZWVkIjoxNiwiYWx0aXR1ZGUiOjIwLCJhbHRpdHVkZUFjY3VyYWN5IjowLjMsImxhdGl0dWRlTG9uZ2l0dWRlQWNjdXJhY3kiOjAuNSwibGF0aXR1ZGUiOjd9LCJzY2hlbWEiOiJpZ2x1OmNvbS5zbm93cGxvd2FuYWx5dGljcy5zbm93cGxvdy9nZW9sb2NhdGlvbl9jb250ZXh0L2pzb25zY2hlbWEvMS0wLTAifV0sInNjaGVtYSI6ImlnbHU6Y29tLnNub3dwbG93YW5hbHl0aWNzLnNub3dwbG93L2NvbnRleHRzL2pzb25zY2hlbWEvMS0wLTAifQ=="""
      val unstructEvent =
        """%7B%22schema%22%3A%22iglu%3Acom.snowplowanalytics.snowplow%2Funstruct_event%2Fjsonschema%2F1-0-0%22%2C%22data%22%3A%7B%22schema%22%3A%22iglu%3Acom.snowplowanalytics.snowplow-website%2Fsignup_form_submitted%2Fjsonschema%2F1-0-0%22%2C%22data%22%3A%7B%22name%22%3A%22Bob%C2%AE%22%2C%22email%22%3A%22alex%2Btest%40snowplowanalytics.com%22%2C%22company%22%3A%22SP%22%2C%22eventsPerMonth%22%3A%22%3C%201%20million%22%2C%22serviceType%22%3A%22unsure%22%7D%7D%7D"""
      val input = BlackBoxTesting.buildCollectorPayload(
        querystring = s"e=ue&cx=$contexts&ue_pr=$unstructEvent".some,
        path = "/i",
        userAgent = "Mozilla/5.0%20(Windows%20NT%206.1;%20WOW64;%20rv:12.0)%20Gecko/20100101%20Firefox/12.0".some,
        refererUri = "http://www.pb.com/oracles/119.html?view=print#detail".some
      )
      val expected = Map(
        "unstruct_event" -> json"""{"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0","data":{"schema":"iglu:com.snowplowanalytics.snowplow-website/signup_form_submitted/jsonschema/1-0-0","data":{"name":"Bob®","email":"alex+test@snowplowanalytics.com","company":"SP","eventsPerMonth":"< 1 million","serviceType":"unsure"}}}""".noSpaces,
        "contexts" -> json"""{"data":[{"data":{"osType":"OSX","appleIdfa":"some_appleIdfa","openIdfa":"some_Idfa","carrier":"some_carrier","deviceModel":"large","osVersion":"3.0.0","appleIdfa":"some_appleIdfa","androidIdfa":"some_androidIdfa","deviceManufacturer":"Amstrad"},"schema":"iglu:com.snowplowanalytics.snowplow/mobile_context/jsonschema/1-0-0"},{"data":{"longitude":10,"bearing":50,"speed":16,"altitude":20,"altitudeAccuracy":0.3,"latitudeLongitudeAccuracy":0.5,"latitude":7},"schema":"iglu:com.snowplowanalytics.snowplow/geolocation_context/jsonschema/1-0-0"}],"schema":"iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1"}""".noSpaces,
        "derived_contexts" -> json"""{"schema":"iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1","data":[{"schema":"iglu:com.statusgator/status_change/jsonschema/1-0-0","data":{"serviceName": "sp-api-request-enrichment"}}]}""".noSpaces
      )
      BlackBoxTesting.runTest(input, expected, Some(ApiRequestEnrichmentSpec.conf))
    }
  }
}

object ApiRequestEnrichmentSpec {
  val conf = json"""
    {
      "schema": "iglu:com.snowplowanalytics.snowplow.enrichments/api_request_enrichment_config/jsonschema/1-0-0",
      "data": {
        "vendor": "com.snowplowanalytics.snowplow.enrichments",
        "name": "api_request_enrichment_config",
        "enabled": true,
        "parameters": {
          "inputs": [
            {
              "key": "uhost",
              "pojo": {
                "field": "page_urlhost"
              }
            },
            {
              "key": "device",
              "json": {
                "field": "contexts",
                "schemaCriterion": "iglu:com.snowplowanalytics.snowplow/mobile_context/jsonschema/1-*-*",
                "jsonPath": "$$.deviceModel"
              }
            },
            {
              "key": "service",
              "json": {
                "field": "unstruct_event",
                "schemaCriterion": "iglu:com.snowplowanalytics.snowplow-website/signup_form_submitted/jsonschema/1-0-*",
                "jsonPath": "$$.serviceType"
              }
            }
          ],
          "api": {
            "http": {
              "method": "GET",
              "uri": "http://localhost:8000/guest/users/{{device}}/{{uhost}}/{{service}}?format=json",
              "timeout": 2000,
              "authentication": { }
            }
          },
          "outputs": [
            {
              "schema": "iglu:com.statusgator/status_change/jsonschema/1-0-0" ,
              "json": {
                "jsonPath": "$$"
              }
            }
          ],
          "cache": {
            "size": 2,
            "ttl": 60
          }
        }
      }
    }
    """
}
