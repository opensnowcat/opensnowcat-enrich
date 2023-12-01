/*
 * Copyright (c) 2012-2022 Snowplow Analytics Ltd. All rights reserved.
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

package com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.apirequest

import com.snowplowanalytics.iglu.client.CirceValidator
import com.snowplowanalytics.snowplow.enrich.common.utils.JsonPath.query
import io.circe.Json
import io.circe.literal.JsonStringContext
import org.specs2.Specification
import org.specs2.matcher.ValidatedMatchers
import org.specs2.specification.core.SpecStructure

class ValidatorSpec extends Specification with ValidatedMatchers {
  override def is: SpecStructure = s2"""
    validate integer field using a valid long value (maximum long)               $e1
    validate integer field using a valid long value (minimum long)               $e2
    validate number field using a positive float value                           $e3
    validate number field using a negative float value                           $e4
    validate number field using a negative double value                          $e5
    validate number field using a positive double value                          $e6
    invalidate integer field using a positive double value                       $e7
  """

  val schema =
    json"""{ "type": "object", "properties": { "orderID": { "type": "integer" }, "price": { "type": "number" } }, "additionalProperties": false }"""

  def e1 =
    query("$", json"""{"orderID": 9223372036854775807 }""")
      .flatMap(fb => CirceValidator.validate(fb.head, schema)) must beRight

  def e2 =
    query("$", json"""{"orderID": -9223372036854775808 }""")
      .flatMap(fb => CirceValidator.validate(fb.head, schema)) must beRight

  def e3 =
    query("$", json"""{"price": ${Json.fromFloatOrString(88.92f)} }""")
      .flatMap(fb => CirceValidator.validate(fb.head, schema)) must beRight

  def e4 =
    query("$", json"""{"price": ${Json.fromFloatOrString(-34345328.72f)} }""")
      .flatMap(fb => CirceValidator.validate(fb.head, schema)) must beRight

  def e5 =
    query("$", json"""{"price": ${Json.fromDoubleOrString(-34345488.72)} }""")
      .flatMap(fb => CirceValidator.validate(fb.head, schema)) must beRight

  def e6 =
    query("$", json"""{"price": ${Json.fromDoubleOrString(32488.72)} }""")
      .flatMap(fb => CirceValidator.validate(fb.head, schema)) must beRight

  def e7 =
    query("$", json"""{"orderID": ${Json.fromDoubleOrString(32488.72)} }""")
      .flatMap(fb => CirceValidator.validate(fb.head, schema)) must beLeft
}
