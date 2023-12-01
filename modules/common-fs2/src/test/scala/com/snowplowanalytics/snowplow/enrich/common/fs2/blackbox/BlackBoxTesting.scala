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
package com.snowplowanalytics.snowplow.enrich.common.fs2.blackbox

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

import org.specs2.mutable.Specification

import cats.effect.Blocker
import cats.effect.IO

import cats.effect.testing.specs2.CatsIO

import cats.data.Validated
import cats.data.Validated.{Invalid, Valid}

import io.circe.Json
import io.circe.syntax._
import io.circe.parser.{parse => jparse}

import org.apache.thrift.TSerializer

import org.http4s.client.{Client => Http4sClient, JavaNetClientBuilder}

import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload

import com.snowplowanalytics.iglu.client.IgluCirceClient
import com.snowplowanalytics.iglu.client.resolver.registries.Registry

import com.snowplowanalytics.iglu.core.{SchemaKey, SchemaVer, SelfDescribingData}
import com.snowplowanalytics.iglu.core.circe.implicits._

import com.snowplowanalytics.snowplow.enrich.common.utils.{BlockerF, ShiftExecution}
import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import com.snowplowanalytics.snowplow.enrich.common.enrichments.EnrichmentRegistry

import com.snowplowanalytics.snowplow.enrich.common.fs2.Enrich
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.FeatureFlags

import com.snowplowanalytics.snowplow.enrich.common.fs2.EnrichSpec
import com.snowplowanalytics.snowplow.enrich.common.fs2.SpecHelpers.createIgluClient
import com.snowplowanalytics.snowplow.enrich.common.fs2.test.TestEnvironment

object BlackBoxTesting extends Specification with CatsIO {

  val blocker: Blocker = Blocker.liftExecutionContext(ExecutionContext.global)
  implicit val httpClient: Http4sClient[IO] = JavaNetClientBuilder[IO](blocker).create

  private val serializer: TSerializer = new TSerializer()

  def buildCollectorPayload(
    body: Option[String] = None,
    contentType: Option[String] = None,
    headers: List[String] = Nil,
    ipAddress: String = "",
    networkUserId: String = java.util.UUID.randomUUID().toString,
    path: String = "",
    querystring: Option[String] = None,
    refererUri: Option[String] = None,
    timestamp: Long = 0L,
    userAgent: Option[String] = None
  ): Array[Byte] = {
    val cp = new CollectorPayload(
      "iglu:com.snowplowanalytics.snowplow/CollectorPayload/thrift/1-0-0",
      ipAddress,
      timestamp,
      "UTF-8",
      "ssc"
    )
    cp.body = body.orNull
    cp.contentType = contentType.orNull
    cp.hostname = "hostname"
    cp.headers = headers.asJava
    cp.ipAddress = ipAddress
    cp.networkUserId = networkUserId
    cp.path = path
    cp.querystring = querystring.orNull
    cp.refererUri = refererUri.orNull
    cp.userAgent = userAgent.orNull
    serializer.serialize(cp)
  }

  def runTest(
    input: Array[Byte],
    expected: Map[String, String],
    enrichmentConfig: Option[Json] = None
  ) =
    createIgluClient(List(Registry.EmbeddedRegistry))
      .flatMap { igluClient =>
        Enrich
          .enrichWith(getEnrichmentRegistry(enrichmentConfig, igluClient),
                      TestEnvironment.adapterRegistry,
                      igluClient,
                      None,
                      EnrichSpec.processor,
                      featureFlags,
                      IO.unit
          )(
            input
          )
          .map {
            case (List(Validated.Valid(enriched)), _) => checkEnriched(enriched, expected)
            case other => ko(s"there should be one enriched event but got $other")
          }
      }

  private def checkEnriched(enriched: EnrichedEvent, expectedFields: Map[String, String]) = {
    val asMap = getMap(enriched)
    val r = expectedFields.map {
      case (k, v) if k == "unstruct_event" || k == "contexts" || k == "derived_contexts" =>
        compareJsons(asMap.getOrElse(k, ""), v) must beTrue
      case (k, v) =>
        asMap.get(k) must beSome(v)
    }
    r.toList.reduce(_ and _)
  }

  private def compareJsons(j1: String, j2: String): Boolean =
    j1 == j2 || jparse(j1).toOption.get == jparse(j2).toOption.get

  private val enrichedFields = classOf[EnrichedEvent].getDeclaredFields()
  enrichedFields.foreach(_.setAccessible(true))

  private def getMap(enriched: EnrichedEvent): Map[String, String] =
    enrichedFields.map(f => (f.getName(), Option(f.get(enriched)).map(_.toString).getOrElse(""))).toMap

  private def getEnrichmentRegistry(enrichmentConfig: Option[Json], igluClient: IgluCirceClient[IO]): IO[EnrichmentRegistry[IO]] =
    enrichmentConfig match {
      case None =>
        IO.pure(EnrichmentRegistry[IO]())
      case Some(json) =>
        val enrichmentsSchemaKey =
          SchemaKey("com.snowplowanalytics.snowplow", "enrichments", "jsonschema", SchemaVer.Full(1, 0, 0))
        val enrichmentsJson = SelfDescribingData(enrichmentsSchemaKey, Json.arr(json)).asJson
        for {
          parsed <- EnrichmentRegistry.parse[IO](enrichmentsJson, igluClient, true)
          confs <- parsed match {
                     case Invalid(e) => IO.raiseError(new IllegalArgumentException(s"can't parse enrichmentsJson: $e"))
                     case Valid(list) => IO.pure(list)
                   }
          built <- EnrichmentRegistry.build[IO](confs, BlockerF.noop, ShiftExecution.noop).value
          registry <- built match {
                        case Left(e) => IO.raiseError(new IllegalArgumentException(s"can't build EnrichmentRegistry: $e"))
                        case Right(r) => IO.pure(r)
                      }
        } yield registry
    }

  private val featureFlags = FeatureFlags(acceptInvalid = false, legacyEnrichmentOrder = false, tryBase64Decoding = false)
}
