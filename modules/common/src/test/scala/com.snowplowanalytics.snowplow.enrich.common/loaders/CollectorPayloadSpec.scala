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
package com.snowplowanalytics.snowplow.enrich.common.loaders

import cats.syntax.option._

import org.apache.http.NameValuePair
import org.apache.http.message.BasicNameValuePair
import org.apache.thrift.TSerializer

import org.joda.time.{DateTimeZone, LocalDate}

import org.scalacheck.{Arbitrary, Gen}

import org.specs2.ScalaCheck
import org.specs2.mutable.Specification
import org.specs2.matcher.{DataTables, ValidatedMatchers}

import com.snowplowanalytics.snowplow.badrows.Processor

class CollectorPayloadSpec extends Specification with DataTables with ScalaCheck with ValidatedMatchers {

  // TODO: let's abstract this up to a CollectorApi.parse test
  // (then we can make isIceRequest private again).
  "isIceRequest" should {
    "correctly identify valid Snowplow GET requests" in {
      "SPEC NAME" || "PATH" | "EXP. RESULT" |
        "Valid #1" !! "/i" ! true |
        "Valid #2" !! "/ice.png" ! true |
        "Valid #3" !! "/i?foo=1&bar=2" ! true |
        "Invalid #1" !! "/blah/i" ! false |
        "Invalid #2" !! "i" ! false |> { (_, path, expected) =>
        CollectorPayload.isIceRequest(path) must_== expected
      }
    }
  }

  "toThrift" should {
    implicit val arbitraryPayload: Arbitrary[CollectorPayload] =
      Arbitrary(CollectorPayloadSpec.collectorPayloadGen)

    "be isomorphic to ThriftLoader.toCollectorPayload" >> {
      prop { payload: CollectorPayload =>
        val bytes = CollectorPayloadSpec.thriftSerializer.serialize(payload.toThrift)
        val result = ThriftLoader.toCollectorPayload(bytes, Processor("test", "0.0.1"))
        result must beValid(Some(payload))
      }
    }
  }
}

object CollectorPayloadSpec {

  val thriftSerializer = new TSerializer()

  val apiGen = Gen.oneOf(
    CollectorPayload.Api("com.snowplowanalytics.snowplow", "tp1"),
    CollectorPayload.Api("com.snowplowanalytics.snowplow", "tp2"),
    CollectorPayload.Api("r", "tp2"),
    CollectorPayload.Api("com.snowplowanalytics.iglu", "v1"),
    CollectorPayload.Api("com.mailchimp", "v1")
  )

  val nameValuePair = for {
    k <- Gen.oneOf("qkey", "key2", "key_3", "key-4", "key 5")
    v <- Gen.option(Gen.oneOf("iglu:com.acme/under_score/jsonschema/1-0-3", "foo", "1", "null"))
  } yield new BasicNameValuePair(k, v.orNull)
  val queryParametersGen: Gen[List[NameValuePair]] =
    for {
      n <- Gen.chooseNum(0, 4)
      list <- Gen.listOfN[NameValuePair](n, nameValuePair)
    } yield list

  val contentTypeGen: Gen[String] = Gen.oneOf("text/plain", "application/json", "application/json; encoding=utf-8")

  val source: CollectorPayload.Source = CollectorPayload.Source("host", "UTF-8", "localhost".some)

  val localDateGen: Gen[LocalDate] = Gen.calendar.map(LocalDate.fromCalendarFields)
  val ipGen: Gen[String] = for {
    part1 <- Gen.choose(2, 255)
    part2 <- Gen.choose(0, 255)
    part3 <- Gen.choose(0, 255)
    part4 <- Gen.choose(0, 255)
  } yield s"$part1.$part2.$part3.$part4"
  val headerGen: Gen[String] = for {
    first <- Gen.asciiPrintableStr.map(_.capitalize)
    second <- Gen.option(Gen.asciiPrintableStr.map(_.capitalize))
    key = second.fold(first)(s => s"$first-$s")
    value <- Gen.identifier
  } yield s"$key: $value"
  val contextGen: Gen[CollectorPayload.Context] = for {
    timestamp <- localDateGen.map(_.toDateTimeAtStartOfDay(DateTimeZone.UTC)).map(Option.apply)
    ip <- Gen.option(ipGen)
    userAgent <- Gen.option(Gen.identifier)
    headersN <- Gen.chooseNum(0, 8)
    headers <- Gen.listOfN(headersN, headerGen)
    userId <- Gen.option(Gen.uuid)
  } yield CollectorPayload.Context(timestamp, ip, userAgent, None, headers, userId)

  val collectorPayloadGen: Gen[CollectorPayload] = for {
    api <- apiGen
    kvlist <- queryParametersGen
    contentType <- Gen.option(contentTypeGen)
    body <- Gen.option(Gen.asciiPrintableStr.suchThat(_.nonEmpty))
    context <- contextGen
  } yield CollectorPayload(api, kvlist, contentType, body, source, context)
}
