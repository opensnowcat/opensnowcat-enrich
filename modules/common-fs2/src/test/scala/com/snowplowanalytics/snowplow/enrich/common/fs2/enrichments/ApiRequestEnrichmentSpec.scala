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
package com.snowplowanalytics.snowplow.enrich.common.fs2.enrichments

import java.util.Base64

import org.apache.http.message.BasicNameValuePair

import cats.implicits._

import cats.effect.testing.specs2.CatsIO

import org.specs2.mutable.Specification

import fs2.Stream

import io.circe.Json
import io.circe.literal._

import com.snowplowanalytics.iglu.core.{SchemaCriterion, SchemaKey, SchemaVer, SelfDescribingData}

import com.snowplowanalytics.snowplow.analytics.scalasdk.SnowplowEvent.Contexts

import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.EnrichmentConf.ApiRequestConf
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.apirequest.{
  Authentication,
  Cache,
  HttpApi,
  Input,
  JsonOutput,
  Output => RegistryOutput
}

import com.snowplowanalytics.snowplow.enrich.common.fs2.enrichments.ApiRequestEnrichmentSpec.unstructEvent
import com.snowplowanalytics.snowplow.enrich.common.fs2.EnrichSpec
import com.snowplowanalytics.snowplow.enrich.common.fs2.test._

class ApiRequestEnrichmentSpec extends Specification with CatsIO {

  sequential

  "ApiRequestEnrichment" should {
    "add a derived context" in {
      val event =
        json"""{
          "schema": "iglu:com.acme/test/jsonschema/1-0-1",
          "data": {"path": {"id": 3}}
        }"""
      val payload = EnrichSpec.collectorPayload.copy(
        querystring = new BasicNameValuePair("ue_px", unstructEvent(event)) :: EnrichSpec.collectorPayload.querystring
      )
      val input = Stream(payload.toRaw)

      /** Schemas defined at [[SchemaRegistry]] */
      val enrichment = ApiRequestConf(
        SchemaKey("com.acme", "enrichment", "jsonschema", SchemaVer.Full(1, 0, 0)),
        List(Input.Json("key1", "unstruct_event", SchemaCriterion("com.acme", "test", "jsonschema", 1), "$.path.id")),
        HttpApi("GET", "http://localhost:8080/enrichment/api/{{key1}}", 2000, Authentication(None)),
        List(RegistryOutput("iglu:com.acme/output/jsonschema/1-0-0", Some(JsonOutput("$")))),
        Cache(1, 1000),
        ignoreOnError = false
      )

      val expected = Contexts(
        List(
          SelfDescribingData(
            SchemaKey("com.acme", "output", "jsonschema", SchemaVer.Full(1, 0, 0)),
            json"""{"output": "3"}"""
          )
        )
      )

      val testWithHttp = HttpServer.resource *> TestEnvironment.make(input, List(enrichment))
      testWithHttp.use { test =>
        test.run().map {
          case (bad, pii, good) =>
            (bad must be empty)
            (pii must be empty)
            (good.map(_.derived_contexts) must contain(exactly(expected)))
        }
      }
    }
  }
}

object ApiRequestEnrichmentSpec {
  private val encoder = Base64.getEncoder

  def encode(json: Json): String =
    new String(encoder.encode(json.noSpaces.getBytes))

  def unstructEvent(json: Json): String =
    encode(json"""{"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0","data":$json}""")
}
