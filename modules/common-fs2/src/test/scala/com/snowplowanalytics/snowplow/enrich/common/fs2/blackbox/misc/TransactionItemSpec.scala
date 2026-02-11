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
package com.snowplowanalytics.snowplow.enrich.common.fs2.blackbox.misc

import org.specs2.mutable.Specification

import cats.effect.testing.specs2.CatsEffect

import cats.syntax.option._

import com.snowplowanalytics.snowplow.enrich.common.fs2.blackbox.BlackBoxTesting

class TransactionItemSpec extends Specification with CatsEffect {
  "enrichWith" should {
    "enrich transaction items" in {
      val querystring =
        "e=ti&ti_id=order-123&ti_sk=PBZ1001&ti_na=Blue%20t-shirt&ti_ca=APPAREL&ti_pr=2000&ti_qu=2"
      val input = BlackBoxTesting.buildCollectorPayload(
        path = "/ice.png",
        querystring = querystring.some
      )
      val expected = Map(
        "event_vendor" -> "com.snowplowanalytics.snowplow",
        "event_name" -> "transaction_item",
        "event_format" -> "jsonschema",
        "event_version" -> "1-0-0",
        "event" -> "transaction_item",
        "ti_orderid" -> "order-123",
        "ti_sku" -> "PBZ1001",
        "ti_quantity" -> "2",
        "ti_currency" -> "",
        "ti_category" -> "APPAREL",
        "ti_price" -> "2000",
        "ti_price_base" -> ""
      )
      BlackBoxTesting.runTest(input, expected)
    }
  }
}
