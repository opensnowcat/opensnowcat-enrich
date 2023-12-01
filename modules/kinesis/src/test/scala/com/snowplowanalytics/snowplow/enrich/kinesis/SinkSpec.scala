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
package com.snowplowanalytics.snowplow.enrich.kinesis

import org.specs2.mutable.Specification

class SinkSpec extends Specification {

  private def getSize(record: String) = record.length

  "group" should {
    "leave a list untouched if its number of records and its size are below the limits" in {
      val records = List("a", "b", "c")
      val expected = List(records)
      val actual = Sink.group(records, 3, 4, getSize)
      actual.reverse.map(_.reverse) must beEqualTo(expected)
    }

    "split a list if it reaches recordLimit" in {
      val expected = List(List("a", "b", "c"), List("d", "e", "f"), List("g"))
      val records = expected.flatten
      val actual = Sink.group(records, 3, 4, getSize)
      actual.reverse.map(_.reverse) must beEqualTo(expected)
    }

    "split a list if it reaches sizeLimit" in {
      val expected = List(List("aa", "bb", "cc"), List("dd", "ee", "ff"), List("g"))
      val records = expected.flatten
      val actual = Sink.group(records, 3, 6, getSize)
      actual.reverse.map(_.reverse) must beEqualTo(expected)
    }

    "split a list if it reaches recordLimit and then sizeLimit" in {
      val expected = List(List("a", "b", "c"), List("dd", "ee", "ff"), List("g"))
      val records = expected.flatten
      val actual = Sink.group(records, 3, 6, getSize)
      actual.reverse.map(_.reverse) must beEqualTo(expected)
    }
  }
}
