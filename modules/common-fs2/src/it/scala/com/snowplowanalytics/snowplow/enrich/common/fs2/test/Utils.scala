/*
 * Copyright (c) 2022-2023 Snowplow Analytics Ltd. All rights reserved.
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

import cats.syntax.either._

import io.circe.parser

import com.snowplowanalytics.snowplow.badrows.BadRow
import com.snowplowanalytics.iglu.core.SelfDescribingData
import com.snowplowanalytics.iglu.core.circe.implicits._

object Utils {

  def parseBadRow(s: String): Either[String, BadRow] =
    for {
      json <- parser.parse(s).leftMap(_.message)
      sdj <- SelfDescribingData.parse(json).leftMap(_.message("Can't decode JSON as SDJ"))
      br <- sdj.data.as[BadRow].leftMap(_.getMessage())
    } yield br
}
