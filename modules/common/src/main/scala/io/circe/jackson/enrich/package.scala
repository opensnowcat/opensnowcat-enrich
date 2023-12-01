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

package io.circe.jackson

import com.fasterxml.jackson.core.{JsonFactory, JsonParser}
import com.snowplowanalytics.snowplow.enrich.common.utils.CirceUtils
import io.circe.{Json, ParsingFailure}

import scala.util.control.NonFatal

/**
 * This package is created to be able to change semantics of
 * circe-jackson library in special cases by creating a custom
 * ObjectMapper.
 *
 * These special cases include circe-jackson #217, #65 and #49
 *
 * This package is to be removed either when jackson is replaced
 * with another parser, possibly jawn, or when circe-jackson has
 * a new version that we can use out of the box.
 */
package object enrich extends JacksonCompat {

  val jsonFactory: JsonFactory = new JsonFactory(CirceUtils.mapper)

  def jsonStringParser(input: String): JsonParser = jsonFactory.createParser(input)

  def parse(input: String): Either[ParsingFailure, Json] =
    try Right(CirceUtils.mapper.readValue(jsonStringParser(input), classOf[Json]))
    catch {
      case NonFatal(error) => Left(ParsingFailure(error.getMessage, error))
    }
}
