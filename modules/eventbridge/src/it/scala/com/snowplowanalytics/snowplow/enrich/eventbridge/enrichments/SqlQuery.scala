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

case object SqlQuery extends Enrichment {
  val config = """
    {
      "schema": "iglu:com.snowplowanalytics.snowplow.enrichments/sql_query_enrichment_config/jsonschema/1-0-0",
      "data": {
        "vendor": "com.snowplowanalytics.snowplow.enrichments",
        "name": "sql_query_enrichment_config",
        "enabled": true,
        "parameters": {
          "inputs": [],
          "database": {
            "mysql": {
              "host": "mysql",
              "port": 3306,
              "sslMode": false,
              "database": "snowplow",
              "username": "enricher",
              "password": "supersecret1"
            }
          },
          "query": {
            "sql": "SELECT * FROM coordinates LIMIT 1"
          },
          "output": {
            "expectedRows": "AT_MOST_ONE",
            "json": {
              "schema": "iglu:com.snowplowanalytics.snowplow/geolocation_context/jsonschema/1-1-0",
              "describes": "ALL_ROWS",
              "propertyNames": "AS_IS"
            }
          },
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
    "geolocation_context",
    "jsonschema",
    SchemaVer.Full(1, 1, 0)
  )
}