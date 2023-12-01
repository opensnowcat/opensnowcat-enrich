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

import java.util.Base64.getEncoder

import com.monovore.decline.Argument

import org.specs2.mutable.Specification

class Base64HoconSpec extends Specification {
  "Argument[Base64Hocon]" should {
    "parse a base64-encoded HOCON as a source" in {
      val inputStr = """input = {}"""
      val input = getEncoder.encodeToString(inputStr.getBytes())
      Argument[Base64Hocon].read(input).toEither must beRight
    }

    "not parse any other base-64 encoded string as a HOCON source" in {
      val inputStr = "xyz"
      val input = getEncoder.encodeToString(inputStr.getBytes())
      Argument[Base64Hocon].read(input).toEither must beLeft
    }
  }
}
