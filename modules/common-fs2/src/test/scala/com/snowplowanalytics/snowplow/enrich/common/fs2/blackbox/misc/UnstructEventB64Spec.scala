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

import io.circe.literal._

import org.specs2.mutable.Specification

import cats.effect.testing.specs2.CatsIO

import cats.syntax.option._

import com.snowplowanalytics.snowplow.enrich.common.fs2.blackbox.BlackBoxTesting

class UnstructEventB64Spec extends Specification with CatsIO {
  "enrichWith" should {
    "enrich unstruct events base64 encoded" in {
      val querystring =
        "e=ue&ue_px=eyJzY2hlbWEiOiJpZ2x1OmNvbS5zbm93cGxvd2FuYWx5dGljcy5zbm93cGxvdy91bnN0cnVjdF9ldmVudC9qc29uc2NoZW1hLzEtMC0wIiwiZGF0YSI6eyJzY2hlbWEiOiJpZ2x1OmNvbS5zbm93cGxvd2FuYWx5dGljcy5zbm93cGxvdy5pbnB1dC1hZGFwdGVycy9zZWdtZW50X3dlYmhvb2tfY29uZmlnL2pzb25zY2hlbWEvMS0wLTAiLCJkYXRhIjp7InZlbmRvciI6Is6nzrHPgc65z4TOr869zrcgTkVXIFVuaWNvZGUgdGVzdCIsIm5hbWUiOiJhbGV4K3Rlc3RAc25vd3Bsb3dhbmFseXRpY3MuY29tIiwicGFyYW1ldGVycyI6eyJtYXBwaW5ncyI6eyJldmVudHNQZXJNb250aCI6IjwgMSBtaWxsaW9uIiwic2VydmljZVR5cGUiOiJ1bnN1cmUifX19fX0="
      val input = BlackBoxTesting.buildCollectorPayload(
        path = "/ice.png",
        querystring = querystring.some
      )
      val expected = Map(
        "event_vendor" -> "com.snowplowanalytics.snowplow.input-adapters",
        "event_name" -> "segment_webhook_config",
        "event_format" -> "jsonschema",
        "event_version" -> "1-0-0",
        "event" -> "unstruct",
        "unstruct_event" -> json"""{"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0","data":{"schema":"iglu:com.snowplowanalytics.snowplow.input-adapters/segment_webhook_config/jsonschema/1-0-0","data":{"vendor":"Χαριτίνη NEW Unicode test","name":"alex+test@snowplowanalytics.com","parameters":{"mappings":{"eventsPerMonth":"< 1 million","serviceType":"unsure"}}}}}""".noSpaces
      )
      BlackBoxTesting.runTest(input, expected)
    }
  }
}
