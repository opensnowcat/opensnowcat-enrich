/*
 * Copyright (c) 2012-2023 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.apirequest

import cats.data.ValidatedNel
import cats.syntax.either._

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect

import io.circe.Json
import io.circe.literal._
import io.circe.parser._

import org.specs2.Specification
import org.specs2.matcher.ValidatedMatchers
import org.specs2.mock.Mockito

import com.snowplowanalytics.iglu.core.{SchemaCriterion, SchemaKey, SchemaVer, SelfDescribingData}

import com.snowplowanalytics.snowplow.badrows.FailureDetails

import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.EnrichmentConf.ApiRequestConf
import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import com.snowplowanalytics.snowplow.enrich.common.utils.HttpClient

class ApiRequestEnrichmentSpec extends Specification with ValidatedMatchers with Mockito with CatsEffect {
  def is = s2"""
  extract correct configuration for GET request and perform the request  $e1
  skip incorrect input (none of json or pojo) in configuration           $e2
  skip incorrect input (both json and pojo) in configuration             $e3
  extract correct configuration for POST request and perform the request $e4
  parse API output with number field successfully                        $e5
  return enrichment failure when API returns an error                    $e6
  return empty list of contexts when API returns an error                $e7
  """

  val SCHEMA_KEY =
    SchemaKey(
      "com.snowplowanalytics.snowplow.enrichments",
      "api_request_enrichment_config",
      "jsonschema",
      SchemaVer.Full(1, 0, 0)
    )

  def e1 = {
    val inputs = List(
      Input.Pojo("user", "user_id"),
      Input.Json(
        "userSession",
        "contexts",
        SchemaCriterion("com.snowplowanalytics.snowplow", "client_session", "jsonschema", 1),
        "$.userId"
      ),
      Input.Pojo("client", "app_id")
    )
    val api =
      HttpApi(
        "GET",
        "http://api.acme.com/users/{{client}}/{{user}}?format=json",
        1000,
        Authentication(Some(HttpBasic(Some("xxx"), None)))
      )
    val mockHttpClient: HttpClient[IO] = new HttpClient[IO] {
      override def getResponse(
        uri: String,
        authUser: Option[String],
        authPassword: Option[String],
        body: Option[String],
        method: String
      ): IO[Either[Throwable, String]] =
        IO.pure("""{"record": {"name": "Fancy User", "company": "Acme"}}""".asRight)
    }
    val output = Output("iglu:com.acme/user/jsonschema/1-0-0", Some(JsonOutput("$.record")))
    val cache = Cache(3000, 60)
    val config = ApiRequestConf(SCHEMA_KEY, inputs, api, List(output), cache, ignoreOnError = false)

    val fakeEnrichedEvent = new EnrichedEvent {
      app_id = "some-fancy-app-id"
      user_id = "some-fancy-user-id"
    }

    val clientSession: SelfDescribingData[Json] = SelfDescribingData[Json](
      SchemaKey(
        "com.snowplowanalytics.snowplow",
        "client_session",
        "jsonschema",
        SchemaVer.Full(1, 0, 1)
      ),
      json"""{
        "data": {
            "userId": "some-fancy-user-session-id",
            "sessionId": "42c8a55b-c0c2-4749-b9ac-09bb0d17d000",
            "sessionIndex": 1,
            "previousSessionId": null,
            "storageMechanism": "COOKIE_1"
        }
      }"""
    )

    val configuration = json"""{
      "vendor": "com.snowplowanalytics.snowplow.enrichments",
      "name": "api_request_enrichment_config",
      "enabled": true,
      "parameters": {
        "inputs": [
          {
            "key": "user",
            "pojo": {
              "field": "user_id"
            }
          },
          {
            "key": "userSession",
            "json": {
              "field": "contexts",
              "schemaCriterion": "iglu:com.snowplowanalytics.snowplow/client_session/jsonschema/1-*-*",
              "jsonPath": "$$.userId"
            }
          },
          {
            "key": "client",
            "pojo": {
              "field": "app_id"
            }
          }
        ],
        "api": {
          "http": {
            "method": "GET",
            "uri": "http://api.acme.com/users/{{client}}/{{user}}?format=json",
            "timeout": 1000,
            "authentication": {
              "httpBasic": {
                "username": "xxx",
                "password": null
              }
            }
          }
        },
        "outputs": [{
          "schema": "iglu:com.acme/user/jsonschema/1-0-0",
          "json": {
            "jsonPath": "$$.record"
          }
        }],
        "cache": {
          "size": 3000,
          "ttl": 60
        }
      }
    }"""
    val validConfig = ApiRequestEnrichment.parse(configuration, SCHEMA_KEY) must beValid(config)

    val user =
      SelfDescribingData(
        SchemaKey("com.acme", "user", "jsonschema", SchemaVer.Full(1, 0, 0)),
        json"""{"name": "Fancy User", "company": "Acme" }"""
      )

    for {
      enrichment <- config.enrichment[IO](mockHttpClient)
      enrichedContextResult <- enrichment.lookup(
                                 event = fakeEnrichedEvent,
                                 derivedContexts = List.empty,
                                 customContexts = List(clientSession),
                                 unstructEvent = None
                               )
    } yield {
      val validResult = enrichedContextResult must beValid(List(user))
      validConfig and validResult
    }
  }

  def e2 = {
    val configuration = json"""{
      "vendor": "com.snowplowanalytics.snowplow.enrichments",
      "name": "api_request_enrichment_config",
      "enabled": true,
      "parameters": {
        "inputs": [
          {
            "key": "user",
            "pojo": {
              "field": "user_id"
            }
          },
          {
            "key": "user"
          },
          {
            "key": "client",
            "pojo": {
              "field": "app_id"
            }
          }
        ],
        "api": {
          "http": {
            "method": "GET",
            "uri": "http://api.acme.com/users/{{client}}/{{user}}?format=json",
            "timeout": 1000,
            "authentication": {
              "httpBasic": {
                "username": "xxx",
                "password": "yyy"
              }
            }
          }
        },
        "outputs": [{
          "schema": "iglu:com.acme/user/jsonschema/1-0-0",
          "json": {
            "jsonPath": "$$.record"
          }
        }],
        "cache": {
          "size": 3000,
          "ttl": 60
        }
      }
    }"""
    ApiRequestEnrichment.parse(configuration, SCHEMA_KEY) must beInvalid
  }

  def e3 = {
    val configuration = json"""{
      "vendor": "com.snowplowanalytics.snowplow.enrichments",
      "name": "api_request_enrichment_config",
      "enabled": true,
      "parameters": {
        "inputs": [
          {
            "key": "user",
            "pojo": {
              "field": "user_id"
            }
          },
          {
            "key": "client",
            "pojo": {
              "field": "app_id"
            },
           "json": {
              "field": "contexts",
              "schemaCriterion": "iglu:com.snowplowanalytics.snowplow/client_session/jsonschema/1-*-*",
              "jsonPath": "$$.userId"
           }
          }
        ],
        "api": {
          "http": {
            "method": "GET",
            "uri": "http://api.acme.com/users/{{client}}/{{user}}?format=json",
            "timeout": 1000,
            "authentication": {
              "httpBasic": {
                "username": "xxx",
                "password": "yyy"
              }
            }
          }
        },
        "outputs": [{
          "schema": "iglu:com.acme/user/jsonschema/1-0-0",
          "json": {
            "jsonPath": "$$.record"
          }
        }],
        "cache": {
          "size": 3000,
          "ttl": 60
        }
      }
    }"""
    ApiRequestEnrichment.parse(configuration, SCHEMA_KEY) must beInvalid
  }

  def e4 = {
    val inputs = List(
      Input.Pojo("user", "user_id"),
      Input.Json(
        "userSession",
        "contexts",
        SchemaCriterion("com.snowplowanalytics.snowplow", "client_session", "jsonschema", 1),
        "$.userId"
      ),
      Input.Pojo("client", "app_id")
    )

    val api = HttpApi(
      method = "POST",
      uri = "http://api.acme.com/users?format=json",
      timeout = 1000,
      authentication = Authentication(Some(HttpBasic(Some("xxx"), None)))
    )

    val mockHttpClient: HttpClient[IO] = new HttpClient[IO] {
      override def getResponse(
        uri: String,
        authUser: Option[String],
        authPassword: Option[String],
        body: Option[String],
        method: String
      ): IO[Either[Throwable, String]] =
        IO.pure("""{"record": {"name": "Fancy User", "company": "Acme"}}""".asRight)
    }
    val output =
      Output(schema = "iglu:com.acme/user/jsonschema/1-0-0", json = Some(JsonOutput("$.record")))
    val cache = Cache(size = 3000, ttl = 60)
    val config = ApiRequestConf(SCHEMA_KEY, inputs, api, List(output), cache, ignoreOnError = false)

    val fakeEnrichedEvent = new EnrichedEvent {
      app_id = "some-fancy-app-id"
      user_id = "some-fancy-user-id"
    }

    val clientSession: SelfDescribingData[Json] = SelfDescribingData(
      SchemaKey(
        "com.snowplowanalytics.snowplow",
        "client_session",
        "jsonschema",
        SchemaVer.Full(1, 0, 1)
      ),
      json"""{
        "data": {
            "userId": "some-fancy-user-session-id",
            "sessionId": "42c8a55b-c0c2-4749-b9ac-09bb0d17d000",
            "sessionIndex": 1,
            "previousSessionId": null,
            "storageMechanism": "COOKIE_1"
        }
      }"""
    )

    val configuration = parse(
      """{
      "vendor": "com.snowplowanalytics.snowplow.enrichments",
      "name": "api_request_enrichment_config",
      "enabled": true,
      "parameters": {
        "inputs": [
          {
            "key": "user",
            "pojo": {
              "field": "user_id"
            }
          },
          {
            "key": "userSession",
            "json": {
              "field": "contexts",
              "schemaCriterion": "iglu:com.snowplowanalytics.snowplow/client_session/jsonschema/1-*-*",
              "jsonPath": "$.userId"
            }
          },
          {
            "key": "client",
            "pojo": {
              "field": "app_id"
            }
          }
        ],
        "api": {
          "http": {
            "method": "POST",
            "uri": "http://api.acme.com/users?format=json",
            "timeout": 1000,
            "authentication": {
              "httpBasic": {
                "username": "xxx",
                "password": null
              }
            }
          }
        },
        "outputs": [{
          "schema": "iglu:com.acme/user/jsonschema/1-0-0",
          "json": {
            "jsonPath": "$.record"
          }
        }],
        "cache": {
          "size": 3000,
          "ttl": 60
        }
      }
   }"""
    ).toOption.get

    val validConfig = ApiRequestEnrichment.parse(configuration, SCHEMA_KEY) must beValid(config)

    val user =
      SelfDescribingData(
        SchemaKey("com.acme", "user", "jsonschema", SchemaVer.Full(1, 0, 0)),
        json"""{"name": "Fancy User", "company": "Acme" }"""
      )

    for {
      enrichment <- config.enrichment[IO](mockHttpClient)
      enrichedContextResult <- enrichment.lookup(
                                 event = fakeEnrichedEvent,
                                 derivedContexts = List.empty,
                                 customContexts = List(clientSession),
                                 unstructEvent = None
                               )
    } yield {
      val validResult = enrichedContextResult must beValid(List(user))
      validConfig and validResult
    }
  }

  def e5 = {
    val inputs = List()
    val api =
      HttpApi(
        "GET",
        "http://api.acme.com/geo?format=json",
        1000,
        Authentication(None)
      )
    val mockHttpClient: HttpClient[IO] = new HttpClient[IO] {
      override def getResponse(
        uri: String,
        authUser: Option[String],
        authPassword: Option[String],
        body: Option[String],
        method: String
      ): IO[Either[Throwable, String]] =
        IO.pure("""{"latitude":32.234,"longitude":33.564}""".asRight)
    }
    val output = Output("iglu:com.acme/geo/jsonschema/1-0-0", Some(JsonOutput("$")))
    val cache = Cache(3000, 60)
    val config = ApiRequestConf(SCHEMA_KEY, inputs, api, List(output), cache, ignoreOnError = false)

    val expectedDerivation =
      SelfDescribingData(
        SchemaKey("com.acme", "geo", "jsonschema", SchemaVer.Full(1, 0, 0)),
        json"""{"latitude": 32.234, "longitude": 33.564}"""
      )

    for {
      enrichment <- config.enrichment[IO](mockHttpClient)
      enrichedContextResult <- enrichment.lookup(new EnrichedEvent, Nil, Nil, None)
    } yield enrichedContextResult must beValid(List(expectedDerivation))
  }

  def e6 =
    failingLookup(ignoreOnError = false).map(_ must beInvalid)

  def e7 =
    failingLookup(ignoreOnError = true).map(_ must beValid(List.empty))

  private def failingLookup(ignoreOnError: Boolean): IO[ValidatedNel[FailureDetails.EnrichmentFailure, List[SelfDescribingData[Json]]]] = {
    val inputs = List()
    val api = HttpApi("GET", "unused", 1000, Authentication(None))
    val mockHttpClient: HttpClient[IO] = new HttpClient[IO] {
      override def getResponse(
        uri: String,
        authUser: Option[String],
        authPassword: Option[String],
        body: Option[String],
        method: String
      ): IO[Either[Throwable, String]] =
        IO.pure(Left(new RuntimeException("API failed!!!")))
    }
    val output = Output("unused", None)
    val cache = Cache(3000, 60)
    val config = ApiRequestConf(SCHEMA_KEY, inputs, api, List(output), cache, ignoreOnError)

    for {
      enrichment <- config.enrichment[IO](mockHttpClient)
      result <- enrichment.lookup(new EnrichedEvent, Nil, Nil, None)
    } yield result
  }
}
