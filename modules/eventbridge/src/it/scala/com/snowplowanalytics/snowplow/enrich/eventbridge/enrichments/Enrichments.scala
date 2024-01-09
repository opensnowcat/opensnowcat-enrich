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
package com.snowplowanalytics.snowplow.enrich.eventbridge.enrichments

import java.util.Base64

object Enrichments {
  private val base64Encoder = Base64.getEncoder()

  def mkJson(enrichments: List[String]): String = {
    val raw = s"""
      {
        "schema": "iglu:com.snowplowanalytics.snowplow/enrichments/jsonschema/1-0-0",
        "data": [
          ${enrichments.mkString(",")}
        ]
      }
    """
    new String(base64Encoder.encode(raw.getBytes()))
  }
}
