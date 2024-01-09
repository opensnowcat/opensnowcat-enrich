/*
 * Copyright (c) 2023-2023 Snowplow Analytics Ltd. All rights reserved.
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

import com.snowplowanalytics.iglu.core.{SchemaKey, SchemaVer}

case object ApiRequest extends Enrichment {
  val config = """
    {
      "schema": "iglu:com.snowplowanalytics.snowplow.enrichments/api_request_enrichment_config/jsonschema/1-0-0",
      "data": {
        "vendor": "com.snowplowanalytics.snowplow.enrichments",
        "name": "api_request_enrichment_config",
        "enabled": true,
        "parameters": {
          "inputs": [],
          "api": {
            "http": {
              "method": "GET",
              "uri": "http://api",
              "timeout": 2000,
              "authentication": {
                "httpBasic": {
                  "username": "xxxxx",
                  "password": "yyyyy"
                }
              }
            }
          },
          "outputs": [
            {
              "schema": "iglu:com.snowplowanalytics.snowplow/diagnostic_error/jsonschema/1-0-0",
              "json": {
                "jsonPath": "$"
              }
            }
          ],
          "cache": {
            "size": 3000,
            "ttl": 60
          }
        }
      }
    }
  """

  val outputSchema = SchemaKey(
    "com.snowplowanalytics.snowplow",
    "diagnostic_error",
    "jsonschema",
    SchemaVer.Full(1, 0, 0)
  )
}
