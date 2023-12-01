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

import java.net.URI

import cats.syntax.either._

import _root_.io.circe.{Decoder, Encoder}
import _root_.io.circe.generic.extras.semiauto._

case class Sentry(dsn: URI)

object Sentry {

  implicit val javaNetUriDecoder: Decoder[URI] =
    Decoder[String].emap { str =>
      Either.catchOnly[IllegalArgumentException](URI.create(str)).leftMap(_.getMessage)
    }

  implicit val javaNetUriEncoder: Encoder[URI] =
    Encoder[String].contramap(_.toString)

  implicit val sentryDecoder: Decoder[Sentry] =
    deriveConfiguredDecoder[Sentry]
  implicit val sentryEncoder: Encoder[Sentry] =
    deriveConfiguredEncoder[Sentry]
}
