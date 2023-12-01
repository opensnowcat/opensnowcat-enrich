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

import cats.effect.testing.specs2.CatsIO

import cats.syntax.option._

import com.snowplowanalytics.snowplow.enrich.common.fs2.blackbox.BlackBoxTesting

class StructEventSpec extends Specification with CatsIO {
  "enrichWith" should {
    "enrich struct events" in {
      val querystring =
        "e=se&se_ca=ecomm&se_ac=add-to-basket&se_la=%CE%A7%CE%B1%CF%81%CE%B9%CF%84%CE%AF%CE%BD%CE%B7&se_pr=1&se_va=35708.23"
      val input = BlackBoxTesting.buildCollectorPayload(
        path = "/ice.png",
        querystring = querystring.some
      )
      val expected = Map(
        "event_vendor" -> "com.google.analytics",
        "event_name" -> "event",
        "event_format" -> "jsonschema",
        "event_version" -> "1-0-0",
        "event" -> "struct",
        "se_action" -> "add-to-basket",
        "se_category" -> "ecomm",
        "se_label" -> "Χαριτίνη",
        "se_property" -> "1",
        "se_value" -> "35708.23"
      )
      BlackBoxTesting.runTest(input, expected)
    }
  }
}
