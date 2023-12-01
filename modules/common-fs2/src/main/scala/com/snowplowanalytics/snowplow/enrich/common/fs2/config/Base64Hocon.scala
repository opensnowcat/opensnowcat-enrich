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

import cats.data.ValidatedNel
import cats.implicits._

import _root_.io.circe.Decoder
import _root_.io.circe.config.syntax._

import com.typesafe.config.{ConfigFactory, Config => TSConfig}

import com.monovore.decline.Argument

// "unresolved" means that substitutions have not been performed yet
final case class Base64Hocon(unresolved: TSConfig) extends AnyVal

object Base64Hocon {

  private val base64 = Base64.getDecoder

  implicit val base64Hocon: Argument[Base64Hocon] =
    new Argument[Base64Hocon] {
      def read(string: String): ValidatedNel[String, Base64Hocon] =
        parseHocon(string).toValidatedNel

      def defaultMetavar: String = "base64"
    }

  def parseHocon(str: String): Either[String, Base64Hocon] =
    for {
      bytes <- Either.catchOnly[IllegalArgumentException](base64.decode(str)).leftMap(_.getMessage)
      tsConfig <- Either.catchNonFatal(ConfigFactory.parseString(new String(bytes, StandardCharsets.UTF_8))).leftMap(_.getMessage)
    } yield Base64Hocon(tsConfig)

  def resolve[A: Decoder](in: Base64Hocon, fallbacks: TSConfig => TSConfig): Either[String, A] = {
    val either = for {
      resolved <- Either.catchNonFatal(in.unresolved.resolve).leftMap(_.getMessage)
      merged <- Either.catchNonFatal(fallbacks(resolved)).leftMap(_.getMessage)
      parsed <- merged.as[A].leftMap(_.show)
    } yield parsed
    either.leftMap(e => s"Cannot parse base64 encoded hocon: $e")
  }
}
