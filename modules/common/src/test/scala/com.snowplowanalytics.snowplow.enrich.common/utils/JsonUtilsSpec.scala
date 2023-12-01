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
package com.snowplowanalytics.snowplow.enrich.common
package utils

import org.specs2.Specification

import org.joda.time.format.DateTimeFormat

import io.circe.Json

import cats.data.NonEmptyList

class JsonUtilsSpec extends Specification {
  def is = s2"""
  toJson can deal with non-null String    $e1
  toJson can deal with null String        $e2
  toJson can deal with booleans           $e3
  toJson can deal with integers           $e4
  toJson can deal with dates              $e5
  """

  def e1 = {
    val key = "key"
    val value = "value"
    JsonUtils.toJson(key, Option(value), Nil, Nil, None) must
      beEqualTo((key, Json.fromString(value)))
  }

  def e2 = {
    val key = "key"
    val value: String = null
    JsonUtils.toJson(key, Option(value), Nil, Nil, None) must
      beEqualTo((key, Json.Null))
  }

  def e3 = {
    val key = "field"

    val truE = "true"
    val exp1 = JsonUtils.toJson(key, Option(truE), List(key), Nil, None) must
      beEqualTo(key -> Json.True)

    val falsE = "false"
    val exp2 = JsonUtils.toJson(key, Option(falsE), List(key), Nil, None) must
      beEqualTo(key -> Json.False)

    val foo = "foo"
    val exp3 = JsonUtils.toJson(key, Option(foo), List(key), Nil, None) must
      beEqualTo(key -> Json.fromString(foo))

    exp1 and exp2 and exp3
  }

  def e4 = {
    val key = "field"

    val number = 123
    val exp1 = JsonUtils.toJson(key, Option(number.toString()), Nil, List(key), None) must
      beEqualTo(key -> Json.fromBigInt(number))

    val notNumber = "abc"
    val exp2 = JsonUtils.toJson(key, Option(notNumber), Nil, List(key), None) must
      beEqualTo(key -> Json.fromString(notNumber))

    exp1 and exp2
  }

  def e5 = {
    val key = "field"

    val formatter = DateTimeFormat.forPattern("yyyy-MM-dd")
    val malformedDate = "2020-09-02"
    val correctDate = "2020-09-02T22:00:00.000Z"

    val exp1 = JsonUtils.toJson(key, Option(malformedDate), Nil, Nil, Some(NonEmptyList.one(key) -> formatter)) must
      be !== (key -> Json.fromString(malformedDate))

    val exp2 = JsonUtils.toJson(key, Option(correctDate), Nil, Nil, Some(NonEmptyList.one(key) -> formatter)) must
      beEqualTo(key -> Json.fromString(correctDate))

    exp1 and exp2
  }
}
