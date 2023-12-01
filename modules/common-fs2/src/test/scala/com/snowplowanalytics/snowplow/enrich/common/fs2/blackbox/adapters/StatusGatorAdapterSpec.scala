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
package com.snowplowanalytics.snowplow.enrich.common.fs2.blackbox.adapters

import io.circe.literal._

import org.specs2.mutable.Specification

import cats.effect.testing.specs2.CatsIO

import cats.syntax.option._

import com.snowplowanalytics.snowplow.enrich.common.fs2.blackbox.BlackBoxTesting

class StatusGatorAdapterSpec extends Specification with CatsIO {
  "enrichWith" should {
    "enrich with StatusGatorAdapter" in {
      val body =
        "service_name=Amazon+Web+Services&favicon_url=https%3A%2F%2Fdwxjd9cd6rwno.cloudfront.net%2Ffavicons%2Famazon-web-services.ico&status_page_url=http%3A%2F%2Fstatus.aws.amazon.com%2F&home_page_url=http%3A%2F%2Faws.amazon.com%2F&current_status=warn&last_status=up&occurred_at=2017-11-11T15%3A36%3A18%2B00%3A00"
      val input = BlackBoxTesting.buildCollectorPayload(
        path = "/com.statusgator/v1",
        body = body.some,
        contentType = "application/x-www-form-urlencoded".some
      )
      val expected = Map(
        "v_tracker" -> "com.statusgator-v1",
        "event_vendor" -> "com.statusgator",
        "event_name" -> "status_change",
        "event_format" -> "jsonschema",
        "event_version" -> "1-0-0",
        "event" -> "unstruct",
        "unstruct_event" -> json"""{"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0","data":{"schema":"iglu:com.statusgator/status_change/jsonschema/1-0-0","data":{"lastStatus":"up","statusPageUrl":"http://status.aws.amazon.com/","serviceName":"Amazon Web Services","faviconUrl":"https://dwxjd9cd6rwno.cloudfront.net/favicons/amazon-web-services.ico","occurredAt":"2017-11-11T15:36:18+00:00","homePageUrl":"http://aws.amazon.com/","currentStatus":"warn"}}}""".noSpaces
      )
      BlackBoxTesting.runTest(input, expected)
    }
  }
}
