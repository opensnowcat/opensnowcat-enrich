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
package com.snowplowanalytics.snowplow.enrich.common.fs2.config

import java.util.Base64
import java.nio.charset.StandardCharsets

import org.specs2.mutable.Specification

import cats.effect.testing.specs2.CatsIO

class CliConfigSpec extends Specification with CatsIO {
  "parseHocon" should {
    "parse valid HOCON" in {
      val string = """
           "input": {
             "type": "PubSub"
              "subscription": "projects/test-project/subscriptions/collector-payloads-sub"
           }
          """.stripMargin
      val b64Encoded = Base64.getEncoder.encodeToString(string.getBytes(StandardCharsets.UTF_8))
      Base64Hocon.parseHocon(b64Encoded) must beRight
    }
  }
}
