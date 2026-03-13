/*
 * Copyright (c) 2019-2023 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.common.adapters.registry

import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.Duration

import cats.implicits._
import cats.data.NonEmptyList

import cats.effect.IO

import cats.effect.testing.specs2.CatsEffect

import com.snowplowanalytics.snowplow.badrows._

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}

import io.circe.Json
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._

import org.joda.time.DateTime

import org.specs2.Specification
import org.specs2.matcher.ValidatedMatchers
import org.specs2.specification.{AfterAll, BeforeAll}

import com.snowplowanalytics.snowplow.enrich.common.adapters.RawEvent
import com.snowplowanalytics.snowplow.enrich.common.loaders.CollectorPayload
import com.snowplowanalytics.snowplow.enrich.common.utils.HttpClient

import com.snowplowanalytics.snowplow.enrich.common.SpecHelpers
import com.snowplowanalytics.snowplow.enrich.common.SpecHelpers._

class RemoteAdapterSpec extends Specification with ValidatedMatchers with CatsEffect with BeforeAll with AfterAll {

  val mockServerPort = 8091
  val mockServerPath = "myEnrichment"
  val httpServer = localHttpServer(mockServerPort, mockServerPath)

  def adapter(client: HttpClient[IO]) = RemoteAdapter[IO](client, s"http://localhost:$mockServerPort/$mockServerPath")

  override def beforeAll = httpServer.start()

  override def afterAll = httpServer.stop(0)

  def is =
    sequential ^ s2"""
  RemoteAdapter must return any events parsed by this local test adapter                    $e1
  This local enricher (well, any remote enricher) must treat an empty list as an error      $e2
  RemoteAdapter must also return any other errors issued by this local adapter              $e3
  HTTP response contains string that is not a correct JSON, should fail                     $e4
  HTTP response contains well-formatted JSON but without events and error, will fail        $e5
  HTTP response contains well-formatted JSON, events that contains an empty list, will fail $e6
   """

  val actionTimeout = Duration(5, TimeUnit.SECONDS)

  val mockTracker = "testTracker-v0.1"
  val mockPlatform = "srv"
  val mockSchemaKey = "moodReport"
  val mockSchemaVendor = "org.remoteEnricherTest"
  val mockSchemaName = "moodChange"
  val mockSchemaFormat = "jsonschema"
  val mockSchemaVersion = "1-0-0"

  private def localHttpServer(tcpPort: Int, basePath: String): HttpServer = {
    val httpServer = HttpServer.create(new InetSocketAddress(tcpPort), 0)

    httpServer.createContext(
      s"/$basePath",
      new HttpHandler {
        def handle(exchange: HttpExchange): Unit = {
          val response = MockRemoteAdapter.handle(getBodyAsString(exchange.getRequestBody))
          if (response != "\"server error\"")
            exchange.sendResponseHeaders(200, 0)
          else
            exchange.sendResponseHeaders(500, 0)
          exchange.getResponseBody.write(response.getBytes)
          exchange.getResponseBody.close()
        }
      }
    )
    httpServer
  }

  private def getBodyAsString(body: InputStream): String = {
    val s = new java.util.Scanner(body).useDelimiter("\\A")
    if (s.hasNext) s.next() else ""
  }

  object MockRemoteAdapter {
    val sampleTracker = "testTracker-v0.1"
    val samplePlatform = "srv"
    val sampleSchemaKey = "moodReport"
    val sampleSchemaVendor = "org.remoteEnricherTest"
    val sampleSchemaName = "moodChange"

    def handle(body: String): String =
      (for {
        js <- parse(body).leftMap(_.message)
        payload <- js.as[Payload].leftMap(_.message)
        payloadBody <- payload.body.toRight("no payload body")
        payloadBodyJs <- parse(payloadBody).leftMap(_.message)
        array <- payloadBodyJs.asArray.toRight("payload body is not an array")
        events = array.map { item =>
                   val json = Json.obj(
                     "schema" := "iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
                     "data" := Json.obj(
                       "schema" := s"iglu:$mockSchemaVendor/$mockSchemaName/$mockSchemaFormat/$mockSchemaVersion",
                       "data" := item
                     )
                   )
                   Map(
                     "tv" -> sampleTracker,
                     "e" -> "ue",
                     "p" -> payload.queryString.getOrElse("p", samplePlatform),
                     "ue_pr" -> json.noSpaces
                   ) ++ payload.queryString
                 }
      } yield events) match {
        case Right(es) => Response(es.toList.some, None).asJson.noSpaces
        case Left(f) => Response(None, s"server error: $f".some).asJson.noSpaces
      }
  }

  object Shared {
    val api = CollectorPayload.Api("org.remoteEnricherTest", "v1")
    val cljSource = CollectorPayload.Source("clj-tomcat", "UTF-8", None)
    val context = CollectorPayload.Context(
      DateTime.parse("2013-08-29T00:18:48.000+00:00").some,
      "37.157.33.123".some,
      None,
      None,
      List("testHeader: testValue"),
      None
    )
  }

  def e1 = {
    val eventData = NonEmptyList.of(("anonymous", -0.3), ("subscribers", 0.6))
    val eventsAsJson = eventData.map(evt => s"""{"${evt._1}":${evt._2}}""")

    val payloadBody = s"""[${eventsAsJson.toList.mkString(",")}]"""
    val payload =
      CollectorPayload(Shared.api, Nil, None, payloadBody.some, Shared.cljSource, Shared.context)

    val expected = eventsAsJson
      .map { evtJson =>
        RawEvent(
          Shared.api,
          Map(
            "tv" -> mockTracker,
            "e" -> "ue",
            "p" -> mockPlatform,
            "ue_pr" -> s"""{"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0","data":{"schema":"iglu:$mockSchemaVendor/$mockSchemaName/$mockSchemaFormat/$mockSchemaVersion","data":$evtJson}}"""
          ).toOpt,
          None,
          Shared.cljSource,
          Shared.context
        )
      }

    SpecHelpers.httpClient.use { http =>
      adapter(http)
        .toRawEvents(payload, SpecHelpers.client)
        .map(_ must beValid(expected))
    }
  }

  def e2 = {
    val emptyListPayload =
      CollectorPayload(Shared.api, Nil, None, "".some, Shared.cljSource, Shared.context)
    val expected = NonEmptyList.one(
      FailureDetails.AdapterFailure.InputData(
        "body",
        None,
        "empty body: not a valid remote adapter http://localhost:8091/myEnrichment payload"
      )
    )
    SpecHelpers.httpClient.use { http =>
      adapter(http)
        .toRawEvents(emptyListPayload, SpecHelpers.client)
        .map(_ must beInvalid(expected))
    }
  }

  def e3 = {
    val bodylessPayload =
      CollectorPayload(Shared.api, Nil, None, None, Shared.cljSource, Shared.context)
    val expected = NonEmptyList.one(
      FailureDetails.AdapterFailure.InputData(
        "body",
        None,
        "empty body: not a valid remote adapter http://localhost:8091/myEnrichment payload"
      )
    )
    SpecHelpers.httpClient.use { http =>
      adapter(http)
        .toRawEvents(bodylessPayload, SpecHelpers.client)
        .map(_ must beInvalid(expected))
    }
  }

  def e4 = {
    val bodylessPayload =
      CollectorPayload(Shared.api, Nil, None, None, Shared.cljSource, Shared.context)
    val invalidJsonResponse = Right("{invalid json")
    val expected = FailureDetails.AdapterFailure.NotJson(
      "body",
      Some("{invalid json"),
      """[REMOTE_ADAPTER] invalid json: expected " got 'invali...' (line 1, column 2)"""
    )
    SpecHelpers.httpClient.use { http =>
      IO.pure(adapter(http).processResponse(bodylessPayload, invalidJsonResponse) must beLeft(expected))
    }
  }

  def e5 = {
    val bodylessPayload =
      CollectorPayload(Shared.api, Nil, None, None, Shared.cljSource, Shared.context)
    val unexpectedJsonResponse = Right("{\"events\":\"response\"}")
    val expected = FailureDetails.AdapterFailure.InputData(
      "body",
      Some("""{"events":"response"}"""),
      "[REMOTE_ADAPTER] could not be decoded as a list of json objects: Got value '\"response\"' with wrong type, expecting array: DownField(events)"
    )
    SpecHelpers.httpClient.use { http =>
      IO.pure(adapter(http).processResponse(bodylessPayload, unexpectedJsonResponse) must beLeft(expected))
    }
  }

  def e6 = {
    val bodylessPayload =
      CollectorPayload(Shared.api, Nil, None, None, Shared.cljSource, Shared.context)
    val emptyJsonResponse = Right("{\"error\":\"\", \"events\":[]}")
    val expected = FailureDetails.AdapterFailure.InputData(
      "body",
      Some("""{"error":"", "events":[]}"""),
      "[REMOTE_ADAPTER] empty list of events"
    )
    SpecHelpers.httpClient.use { http =>
      IO.pure(adapter(http).processResponse(bodylessPayload, emptyJsonResponse) must beLeft(expected))
    }
  }
}

final case class Payload(
  queryString: Map[String, String],
  headers: List[String],
  body: Option[String],
  contentType: Option[String]
)

final case class Response(events: Option[List[Map[String, String]]], error: Option[String])
