/*
 * Copyright (c) 2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.common.fs2.test

import fs2.Stream

import cats.implicits._

import cats.effect.Sync

import _root_.io.circe.syntax._
import _root_.io.circe.{Encoder, Json, JsonObject}

import org.scalacheck.{Arbitrary, Gen}

import org.joda.time.DateTime

import org.apache.thrift.TSerializer

import java.util.Base64

import com.snowplowanalytics.iglu.core.{ SelfDescribingData, SchemaKey, SchemaVer }
import com.snowplowanalytics.iglu.core.circe.CirceIgluCodecs._

import com.snowplowanalytics.snowplow.enrich.common.loaders.CollectorPayload

object CollectorPayloadGen {

  private val base64Encoder = Base64.getEncoder()

  def generate[F[_]: Sync](nbGoodEvents: Long, nbBadRows: Long = 0L): Stream[F, Array[Byte]] =
    generateRaw(nbGoodEvents, nbBadRows).map(_.toThrift).map(new TSerializer().serialize)

  def generateRaw[F[_]: Sync](nbGoodEvents: Long, nbBadRows: Long): Stream[F, CollectorPayload] =
    Stream.repeatEval(runGen(collectorPayloadGen(true))).take(nbGoodEvents) ++ Stream.repeatEval(runGen(collectorPayloadGen(false))).take(nbBadRows)

  private def collectorPayloadGen(valid: Boolean): Gen[CollectorPayload] =
    for {
      vendor <- Gen.const("com.snowplowanalytics.snowplow")
      version <- Gen.const("tp2")
      api = CollectorPayload.Api(vendor, version)

      queryString = Nil

      contentType = Some("application/json")

      body <- bodyGen(valid).map(Some(_))

      name = "scala-tracker_1.0.0"
      encoding = "UTF8"
      hostname = Some("example.acme")
      source = CollectorPayload.Source(name, encoding, hostname)

      timestamp <- Gen.option(DateTime.now)
      ipAddress <- Gen.option(ipAddressGen)
      useragent <- Gen.option(userAgentGen)
      refererUri = None
      headers = Nil
      userId <- Gen.uuid.map(Some(_))
      context = CollectorPayload.Context(timestamp, ipAddress, useragent, refererUri, headers, userId)
    } yield CollectorPayload(api, queryString, contentType, body, source, context)

  private def bodyGen(valid: Boolean): Gen[String] =
    for {
      p <- Gen.oneOf("web", "mob", "app").withKey("p")
      aid <- Gen.const("enrich-kinesis-integration-tests").withKey("aid")
      e <- Gen.const("ue").withKey("e")
      tv <- Gen.oneOf("scala-tracker_1.0.0", "js_2.0.0", "go_1.2.3").withKey("tv")
      uePx <-
        if(valid)
          ueGen.map(_.toString).map(str => base64Encoder.encodeToString(str.getBytes)).withKey("ue_px")
        else
          Gen.const("foo").withKey("ue_px")
    } yield SelfDescribingData(
      SchemaKey("com.snowplowanalytics.snowplow", "payload_data", "jsonschema", SchemaVer.Full(1,0,4)),
       List(asObject(List(p, aid, e, uePx, tv))).asJson
    ).asJson.toString

  private def ueGen =
    for {
      sdj <- Gen.oneOf(changeFormGen, clientSessionGen)
    } yield SelfDescribingData(
      SchemaKey("com.snowplowanalytics.snowplow", "unstruct_event", "jsonschema", SchemaVer.Full(1,0,0)),
      sdj.asJson
    ).asJson


  private def changeFormGen =
    for {
      formId    <- strGen(32, Gen.alphaNumChar).withKey("formId")
      elementId <- strGen(32, Gen.alphaNumChar).withKey("elementId")
      nodeName  <- Gen.oneOf(List("INPUT", "TEXTAREA", "SELECT")).withKey("nodeName")
      `type`    <- Gen.option(Gen.oneOf(List("button", "checkbox", "color", "date", "datetime", "datetime-local", "email", "file", "hidden", "image", "month", "number", "password", "radio", "range", "reset", "search", "submit", "tel", "text", "time", "url", "week"))).withKeyOpt("type")
      value     <- Gen.option(strGen(16, Gen.alphaNumChar)).withKeyNull("value")
    } yield SelfDescribingData(
      SchemaKey("com.snowplowanalytics.snowplow", "change_form", "jsonschema", SchemaVer.Full(1,0,0)),
      asObject(List(formId, elementId, nodeName, `type`, value))
    )

  private def clientSessionGen =
    for {
      userId            <- Gen.uuid.withKey("userId")
      sessionId         <- Gen.uuid.withKey("sessionId")
      sessionIndex      <- Gen.choose(0, 2147483647).withKey("sessionIndex")
      previousSessionId <- Gen.option(Gen.uuid).withKeyNull("previousSessionId")
      storageMechanism  <- Gen.oneOf(List("SQLITE", "COOKIE_1", "COOKIE_3", "LOCAL_STORAGE", "FLASH_LSO")).withKey("storageMechanism")
    } yield SelfDescribingData(
      SchemaKey("com.snowplowanalytics.snowplow", "client_session", "jsonschema", SchemaVer.Full(1,0,1)),
      asObject(List(userId, sessionId, sessionIndex, previousSessionId, storageMechanism))
    )

  private def strGen(n: Int, gen: Gen[Char]): Gen[String] =
    Gen.chooseNum(1, n).flatMap(len => Gen.listOfN(len, gen).map(_.mkString))

  private def ipAddressGen = Gen.oneOf(ipv4AddressGen, ipv6AddressGen)

  private def ipv4AddressGen =
    for {
      a <- Gen.chooseNum(0, 255)
      b <- Gen.chooseNum(0, 255)
      c <- Gen.chooseNum(0, 255)
      d <- Gen.chooseNum(0, 255)
    } yield s"$a.$b.$c.$d"

  private def ipv6AddressGen =
    for {
      a <- Arbitrary.arbitrary[Short]
      b <- Arbitrary.arbitrary[Short]
      c <- Arbitrary.arbitrary[Short]
      d <- Arbitrary.arbitrary[Short]
      e <- Arbitrary.arbitrary[Short]
      f <- Arbitrary.arbitrary[Short]
      g <- Arbitrary.arbitrary[Short]
      h <- Arbitrary.arbitrary[Short]
    } yield f"$a%x:$b%x:$c%x:$d%x:$e%x:$f%x:$g%x:$h%x"

  private def userAgentGen: Gen[String] =
    Gen.oneOf(
      "Mozilla/5.0 (iPad; CPU OS 6_1_3 like Mac OS X) AppleWebKit/536.26 (KHTML, like Gecko) Version/6.0 Mobile/10B329 Safari/8536.25",
      "Mozilla/5.0 (iPhone; CPU iPhone OS 11_0 like Mac OS X) AppleWebKit/604.1.38 (KHTML, like Gecko) Version/11.0 Mobile/15A372 Safari/604.1",
      "Mozilla/5.0 (Linux; U; Android 2.2; en-us; Nexus One Build/FRF91) AppleWebKit/533.1 (KHTML, like Gecko) Version/4.0 Mobile Safari/533.1",
      "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/535.1 (KHTML, like Gecko) Chrome/13.0.782.112 Safari/535.1",
      "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.0; Trident/5.0)",
      "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
    )

  // Helpers to control null/absence

  private def asObject(fields: List[Option[(String, Json)]]): Json =
    JsonObject.fromIterable(fields.collect { case Some(field) => field }).asJson

  implicit class GenOps[A](gen: Gen[A]) {
    def withKey[B](name: String)(implicit enc: Encoder[A]): Gen[Option[(String, Json)]] =
      gen.map { a => Some((name -> a.asJson)) }
  }

  implicit class GenOptOps[A](gen: Gen[Option[A]]) {
    def withKeyOpt(name: String)(implicit enc: Encoder[A]): Gen[Option[(String, Json)]] =
      gen.map {
        case Some(a) => Some((name -> a.asJson))
        case None => None
      }

    def withKeyNull(name: String)(implicit enc: Encoder[A]): Gen[Option[(String, Json)]] =
      gen.map {
        case Some(a) => Some((name -> a.asJson))
        case None => Some((name -> Json.Null))
      }
  }

  /** Convert `Gen` into `IO` */
  def runGen[F[_]: Sync, A](gen: Gen[A]): F[A] = {
    val MAX_ATTEMPTS = 5
    def go(attempt: Int): F[A] =
      if (attempt >= MAX_ATTEMPTS)
        Sync[F].raiseError(new RuntimeException(s"Couldn't generate an event after $MAX_ATTEMPTS attempts"))
      else
        Sync[F].delay(gen.sample).flatMap {
          case Some(a) => Sync[F].pure(a)
          case None => go(attempt + 1)
        }
    go(1)
  }
}
