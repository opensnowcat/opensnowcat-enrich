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

import cats.effect.testing.specs2.CatsEffect

import cats.syntax.option._

import com.snowplowanalytics.snowplow.enrich.common.fs2.blackbox.BlackBoxTesting

class HubspotAdapterSpec extends Specification with CatsEffect {
  "enrichWith" should {
    "enrich with HubspotAdapter" in {
      val body =
        json"""[{"eventId":1,"subscriptionId":25458,"portalId":4737818,"occurredAt":1539145399845,"subscriptionType":"contact.creation","attemptNumber":0,"objectId":123,"changeSource":"CRM","changeFlag":"NEW","appId":177698}]"""
      val input = BlackBoxTesting.buildCollectorPayload(
        path = "/com.hubspot/v1",
        body = body.noSpaces.some,
        contentType = "application/json".some
      )
      val expected = Map(
        "v_tracker" -> "com.hubspot-v1",
        "event_vendor" -> "com.hubspot",
        "event_name" -> "contact_creation",
        "event_format" -> "jsonschema",
        "event_version" -> "1-0-0",
        "event" -> "unstruct",
        "unstruct_event" -> json"""{"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0","data":{"schema":"iglu:com.hubspot/contact_creation/jsonschema/1-0-0","data":{"eventId":1,"subscriptionId":25458,"portalId":4737818,"occurredAt":"2018-10-10T04:23:19.845Z","attemptNumber":0,"objectId":123,"changeSource":"CRM","changeFlag":"NEW","appId":177698}}}""".noSpaces
      )
      BlackBoxTesting.runTest(input, expected)
    }
  }
}
