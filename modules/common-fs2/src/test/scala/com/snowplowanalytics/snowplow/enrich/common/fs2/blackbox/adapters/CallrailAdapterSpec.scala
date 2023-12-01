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

class CallrailAdapterSpec extends Specification with CatsIO {
  "enrichWith" should {
    "enrich with CallRailAdapter" in {
      val input = BlackBoxTesting.buildCollectorPayload(
        path = "/com.callrail/v1",
        querystring =
          "aid=bnb&answered=true&callercity=BAKERSFIELD&callercountry=US&callername=SKYPE+CALLER&callernum=%2B166&callerstate=CA&callerzip=93307&callsource=keyword&datetime=2014-10-09+16%3A23%3A45&destinationnum=20&duration=247&first_call=true&id=30&ip=86.178.183.7&landingpage=http%3A%2F%2Flndpage.com%2F&recording=http%3A%2F%2Fapp.callrail.com%2Fcalls%2F30%2Frecording%2F9f&referrer=direct&referrermedium=Direct&trackingnum=%2B12015911668".some
      )
      val expected = Map(
        "v_tracker" -> "com.callrail-v1",
        "event_vendor" -> "com.callrail",
        "event_name" -> "call_complete",
        "event_format" -> "jsonschema",
        "event_version" -> "1-0-2",
        "event" -> "unstruct",
        "unstruct_event" -> json"""{"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0","data":{"schema":"iglu:com.callrail/call_complete/jsonschema/1-0-2","data":{"duration":247,"ip":"86.178.183.7","destinationnum":"20","datetime":"2014-10-09T16:23:45.000Z","landingpage":"http://lndpage.com/","callerzip":"93307","callername":"SKYPE CALLER","id":"30","callernum":"+166","trackingnum":"+12015911668","referrermedium":"Direct","referrer":"direct","callerstate":"CA","recording":"http://app.callrail.com/calls/30/recording/9f","first_call":true,"callercountry":"US","callercity":"BAKERSFIELD","answered":true,"callsource":"keyword"}}}""".noSpaces
      )
      BlackBoxTesting.runTest(input, expected)
    }
  }
}
