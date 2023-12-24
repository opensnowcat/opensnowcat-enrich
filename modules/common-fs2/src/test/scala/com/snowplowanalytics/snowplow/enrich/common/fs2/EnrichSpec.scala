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
package com.snowplowanalytics.snowplow.enrich.common.fs2

import java.time.Instant
import java.util.{Base64, UUID}

import scala.concurrent.duration._

import cats.data.{NonEmptyList, Validated}
import cats.implicits._

import cats.effect.IO

import fs2.Stream

import _root_.io.circe.literal._

import org.apache.http.NameValuePair
import org.apache.http.message.BasicNameValuePair

import com.snowplowanalytics.iglu.core.{SchemaKey, SchemaVer}

import com.snowplowanalytics.snowplow.analytics.scalasdk.Event
import com.snowplowanalytics.snowplow.badrows.{BadRow, Failure, FailureDetails, Processor, Payload => BadRowPayload}
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.IpLookupsEnrichment
import com.snowplowanalytics.snowplow.enrich.common.enrichments.MiscEnrichments
import com.snowplowanalytics.snowplow.enrich.common.loaders.CollectorPayload
import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import com.snowplowanalytics.snowplow.enrich.common.utils.ConversionUtils
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.FeatureFlags

import com.snowplowanalytics.snowplow.enrich.common.fs2.EnrichSpec.{Expected, minimalEvent, normalizeResult}
import com.snowplowanalytics.snowplow.enrich.common.fs2.SpecHelpers.createIgluClient
import com.snowplowanalytics.snowplow.enrich.common.fs2.test._

import org.specs2.ScalaCheck
import org.specs2.mutable.Specification

import cats.effect.testing.specs2.CatsIO
import org.specs2.scalacheck.Parameters

class EnrichSpec extends Specification with CatsIO with ScalaCheck {

  sequential

  "enrichWith" should {
    "enrich a minimal page_view CollectorPayload event without any enrichments enabled" in {
      val expected = minimalEvent
        .copy(
          etl_tstamp = Some(Instant.ofEpochMilli(SpecHelpers.StaticTime)),
          v_etl = MiscEnrichments.etlVersion(EnrichSpec.processor),
          user_ipaddress = Some("175.16.199.0"),
          event = Some("page_view"),
          event_vendor = Some("com.snowplowanalytics.snowplow"),
          event_name = Some("page_view"),
          event_format = Some("jsonschema"),
          event_version = Some("1-0-0"),
          derived_tstamp = Some(Instant.ofEpochMilli(0L))
        )
      implicit val c = TestEnvironment.http4sClient
      createIgluClient(List(TestEnvironment.embeddedRegistry)).flatMap { igluClient =>
        Enrich
          .enrichWith(
            TestEnvironment.enrichmentReg.pure[IO],
            TestEnvironment.adapterRegistry,
            igluClient,
            None,
            EnrichSpec.processor,
            EnrichSpec.featureFlags,
            IO.unit
          )(
            EnrichSpec.payload
          )
          .map(normalizeResult)
          .map {
            case List(Validated.Valid(event)) => event must beEqualTo(expected)
            case other => ko(s"Expected one valid event, got $other")
          }
      }

    }

    "enrich a randomly generated page view event" in {
      implicit val cpGen = PayloadGen.getPageViewArbitrary
      implicit val c = TestEnvironment.http4sClient
      prop { (collectorPayload: CollectorPayload) =>
        val payload = collectorPayload.toRaw
        createIgluClient(List(TestEnvironment.embeddedRegistry)).flatMap { igluClient =>
          Enrich
            .enrichWith(
              TestEnvironment.enrichmentReg.pure[IO],
              TestEnvironment.adapterRegistry,
              igluClient,
              None,
              EnrichSpec.processor,
              EnrichSpec.featureFlags,
              IO.unit
            )(
              payload
            )
            .map(normalizeResult)
            .map {
              case List(Validated.Valid(e)) => e.event must beSome("page_view")
              case other => ko(s"Expected one valid event, got $other")
            }
        }
      }.setParameters(Parameters(maxSize = 20, minTestsOk = 25))
    }

    "enrich a base64 encoded event" in {
      val expected = minimalEvent
        .copy(
          etl_tstamp = Some(Instant.ofEpochMilli(SpecHelpers.StaticTime)),
          v_etl = MiscEnrichments.etlVersion(EnrichSpec.processor),
          user_ipaddress = Some("175.16.199.0"),
          event = Some("page_view"),
          event_vendor = Some("com.snowplowanalytics.snowplow"),
          event_name = Some("page_view"),
          event_format = Some("jsonschema"),
          event_version = Some("1-0-0"),
          derived_tstamp = Some(Instant.ofEpochMilli(0L))
        )
      implicit val c = TestEnvironment.http4sClient
      createIgluClient(List(TestEnvironment.embeddedRegistry)).flatMap { igluClient =>
        Enrich
          .enrichWith(
            TestEnvironment.enrichmentReg.pure[IO],
            TestEnvironment.adapterRegistry,
            igluClient,
            None,
            EnrichSpec.processor,
            EnrichSpec.featureFlags.copy(tryBase64Decoding = true),
            IO.unit
          )(
            Base64.getEncoder.encode(EnrichSpec.payload)
          )
          .map(normalizeResult)
          .map {
            case List(Validated.Valid(event)) => event must beEqualTo(expected)
            case other => ko(s"Expected one valid event, got $other")
          }
      }
    }

    "return bad row when base64 decoding isn't enabled and base64 encoded event arrived" in {
      implicit val c = TestEnvironment.http4sClient
      createIgluClient(List(TestEnvironment.embeddedRegistry)).flatMap { igluClient =>
        Enrich
          .enrichWith(
            TestEnvironment.enrichmentReg.pure[IO],
            TestEnvironment.adapterRegistry,
            igluClient,
            None,
            EnrichSpec.processor,
            EnrichSpec.featureFlags,
            IO.unit
          )(
            Base64.getEncoder.encode(EnrichSpec.payload)
          )
          .map(normalizeResult)
          .map {
            case List(Validated.Invalid(badRow)) => println(badRow); ok
            case other => ko(s"Expected one bad row, got $other")
          }
      }
    }
  }

  "enrich" should {
    "update metrics with raw, good and bad counters" in {
      val input = Stream.emits(List(Array.empty[Byte], EnrichSpec.payload))
      implicit val c = TestEnvironment.http4sClient
      TestEnvironment.make(input).use { test =>
        val enrichStream = Enrich.run[IO, Array[Byte]](test.env)
        for {
          _ <- enrichStream.compile.drain
          bad <- test.bad
          good <- test.good
          counter <- test.counter.get
        } yield {
          (counter.raw must_== 2L)
          (counter.good must_== 1L)
          (counter.bad must_== 1L)
          (bad.size must_== 1)
          (good.size must_== 1)
        }
      }
    }

    "enrich event using refreshing MaxMind DB" in {
      // 4 enrichments can update assets: MaxMind, IAB, referer-parser, ua-parser
      val input = Stream(EnrichSpec.payload) ++ Stream.sleep_(2.seconds) ++ Stream(EnrichSpec.payload)
      val ipLookupsConf = IpLookupsEnrichment
        .parse(
          json"""{
                    "name": "ip_lookups",
                    "vendor": "com.snowplowanalytics.snowplow",
                    "enabled": true,
                    "parameters": {
                      "geo": {
                        "database": "GeoIP2-City.mmdb",
                        "uri": "http://localhost:8080/maxmind"
                      }
                    }
                  }""",
          SchemaKey(
            "com.snowplowanalytics.snowplow",
            "ip_lookups",
            "jsonschema",
            SchemaVer.Full(2, 0, 0)
          ),
          false // Unlike in other tests we actually download it
        )
        .getOrElse(throw new RuntimeException("Invalid test configuration"))

      val one = Expected
        .copy(
          geo_country = Some("CN"),
          geo_region = Some("22"),
          geo_city = Some("Changchun"),
          geo_latitude = Some(43.88),
          geo_longitude = Some(125.3228),
          geo_region_name = Some("Jilin Sheng"),
          geo_timezone = Some("Asia/Harbin")
        )
      val two = one.copy(geo_city = Some("Baishan"))
      // Third one is Fuyu

      (HttpServer.resource *> TestEnvironment.make(input, List(ipLookupsConf))).use { test =>
        test
          .run(_.copy(assetsUpdatePeriod = Some(1800.millis)))
          .map {
            case (bad, pii, good) =>
              (bad must be empty)
              (pii must be empty)
              (good must contain(exactly(one, two)))
          }
      }
    }
  }

  "sinkChunk" should {
    "emit an enriched event with attributes to the good sink" in {
      TestEnvironment.make(Stream.empty).use { test =>
        val environment = test.env.copy(goodAttributes = { ee => Map("app_id" -> ee.app_id) })
        val ee = new EnrichedEvent()
        ee.app_id = "test_app"
        ee.platform = "web"

        for {
          _ <- sinkGood(environment, ee)
          good <- test.good
          pii <- test.pii
          bad <- test.bad
        } yield {
          good should beLike {
            case Vector(AttributedData(bytes, pk, attrs)) =>
              bytes must not be empty
              pk must beEqualTo("test_good_partition_key")
              attrs must contain(exactly("app_id" -> "test_app"))
          }

          (pii should be empty)
          (bad should be empty)
        }
      }
    }

    "emit a pii event with attributes to the pii sink" in {
      TestEnvironment.make(Stream.empty).use { test =>
        val environment =
          test.env.copy(goodAttributes = { ee => Map("app_id" -> ee.app_id) }, piiAttributes = { ee => Map("platform" -> ee.platform) })
        val ee = new EnrichedEvent()
        ee.event_id = "some_id"
        ee.app_id = "test_app"
        ee.platform = "web"
        ee.pii = "e30="

        for {
          _ <- sinkGood(environment, ee)
          good <- test.good
          pii <- test.pii
          bad <- test.bad
        } yield {

          good should beLike {
            case Vector(AttributedData(bytes, pk, attrs)) =>
              bytes must not be empty
              pk must beEqualTo("test_good_partition_key")
              attrs must contain(exactly("app_id" -> "test_app"))
          }

          pii should beLike {
            case Vector(AttributedData(bytes, pk, attrs)) =>
              bytes must not be empty
              pk must beEqualTo("test_pii_partition_key")
              attrs must contain(exactly("platform" -> "srv"))
          }

          (bad should be empty)
        }

      }
    }

    "emit a bad row to the bad sink" in {
      TestEnvironment.make(Stream.empty).use { test =>
        val failure = Failure
          .AdapterFailures(Instant.now, "vendor", "1-0-0", NonEmptyList.one(FailureDetails.AdapterFailure.NotJson("field", None, "error")))
        val badRow = BadRow.AdapterFailures(EnrichSpec.processor, failure, EnrichSpec.collectorPayload.toBadRowPayload)

        for {
          _ <- sinkBad(test.env, badRow)
          good <- test.good
          pii <- test.pii
          bad <- test.bad
        } yield {

          (good should be empty)
          (pii should be empty)

          bad should have size 1
        }

      }
    }

    "serialize a bad event to the bad output" in {
      implicit val cpGen = PayloadGen.getPageViewArbitrary
      prop { (collectorPayload: CollectorPayload) =>
        val failure = Failure.AdapterFailures(Instant.now,
                                              "vendor",
                                              "1-0-0",
                                              NonEmptyList.one(FailureDetails.AdapterFailure.NotJson("field", None, "error"))
        )
        val badRow = BadRow.AdapterFailures(EnrichSpec.processor, failure, collectorPayload.toBadRowPayload)

        TestEnvironment.make(Stream.empty).use { test =>
          for {
            _ <- sinkBad(test.env, badRow)
            good <- test.good
            pii <- test.pii
            bad <- test.bad
          } yield {
            (bad.size must_== 1)
            (good should be empty)
            (pii should be empty)
          }
        }
      }
    }

    "serialize a good event to the good output" in {
      val ee = new EnrichedEvent()

      TestEnvironment.make(Stream.empty).use { test =>
        for {
          _ <- sinkGood(test.env, ee)
          good <- test.good
          pii <- test.pii
          bad <- test.bad
        } yield {
          (good.size must_== 1)
          (bad should be empty)
          (pii should be empty)
        }
      }
    }

    "serialize an over-sized good event to the bad output" in {
      val ee = new EnrichedEvent()
      ee.app_id = "x" * 10000000

      TestEnvironment.make(Stream.empty).use { test =>
        for {
          _ <- sinkGood(test.env, ee)
          good <- test.good
          pii <- test.pii
          bad <- test.bad
        } yield {
          bad should beLike {
            case Vector(bytes) =>
              bytes must not be empty
              bytes must have size (be_<=(6900000))
          }
          (good should be empty)
          (pii should be empty)
        }
      }
    }

    "serialize a pii event to the pii output" in {
      val ee = new EnrichedEvent()
      ee.pii = "eyJ4IjoieSJ9Cg=="
      ee.event_id = "some_id"

      TestEnvironment.make(Stream.empty).use { test =>
        for {
          _ <- sinkGood(test.env, ee)
          good <- test.good
          pii <- test.pii
          bad <- test.bad
        } yield {
          (good.size must_== 1)
          (pii.size must_== 1)
          (bad should be empty)
        }
      }
    }

    "not generate a bad row for an over-sized pii event" in {
      val ee = new EnrichedEvent()
      ee.pii = "x" * 10000000
      ee.event_id = "some_id"

      TestEnvironment.make(Stream.empty).use { test =>
        for {
          _ <- sinkGood(test.env, ee)
          good <- test.good
          pii <- test.pii
          bad <- test.bad
        } yield {
          (good.size must_== 1)
          (bad should be empty)
          (pii should be empty)
        }
      }
    }
  }

  def sinkGood(
    environment: Environment[IO, Array[Byte]],
    enriched: EnrichedEvent
  ): IO[Unit] = sinkOne(environment, Validated.Valid(enriched))

  def sinkBad(
    environment: Environment[IO, Array[Byte]],
    badRow: BadRow
  ): IO[Unit] = sinkOne(environment, Validated.Invalid(badRow))

  def sinkOne(
    environment: Environment[IO, Array[Byte]],
    event: Validated[BadRow, EnrichedEvent]
  ): IO[Unit] = Enrich.sinkChunk(List((List(event), None)), environment)
}

object EnrichSpec {
  val eventId: UUID = UUID.fromString("deadbeef-dead-beef-dead-beefdead")
  val processor = Processor("common-fs2-tests", "0.0.0")
  val vCollector = "ssc-test-0.0.0"

  val api: CollectorPayload.Api =
    CollectorPayload.Api("com.snowplowanalytics.snowplow", "tp2")
  val source: CollectorPayload.Source =
    CollectorPayload.Source(vCollector, "UTF-8", Some("collector.snplow.net"))
  val context: CollectorPayload.Context = CollectorPayload.Context(None, Some("175.16.199.0"), None, None, List(), None)
  val querystring: List[NameValuePair] = List(
    new BasicNameValuePair("e", "pv"),
    new BasicNameValuePair("eid", eventId.toString)
  )
  val collectorPayload: CollectorPayload = CollectorPayload(api, querystring, None, None, source, context)
  def payload = collectorPayload.toRaw

  def normalize(payload: String): Validated[BadRow, Event] =
    Event
      .parse(payload)
      .map(_.copy(etl_tstamp = Some(Instant.ofEpochMilli(SpecHelpers.StaticTime)))) match {
      case Validated.Valid(event) =>
        Validated.Valid(event)
      case Validated.Invalid(error) =>
        val rawPayload = BadRowPayload.RawPayload(payload)
        val badRow = BadRow.LoaderParsingError(processor, error, rawPayload)
        Validated.Invalid(badRow)
    }

  def normalizeResult(payload: Result): List[Validated[BadRow, Event]] =
    payload._1.map {
      case Validated.Valid(a) => normalize(ConversionUtils.tabSeparatedEnrichedEvent(a))
      case Validated.Invalid(e) => e.invalid
    }

  val minimalEvent = Event
    .minimal(
      EnrichSpec.eventId,
      Instant.ofEpochMilli(0L),
      vCollector,
      s"${processor.artifact}-${processor.version}"
    )

  val Expected = minimalEvent
    .copy(
      etl_tstamp = Some(Instant.ofEpochMilli(SpecHelpers.StaticTime)),
      v_etl = MiscEnrichments.etlVersion(EnrichSpec.processor),
      user_ipaddress = Some("175.16.199.0"),
      event = Some("page_view"),
      event_vendor = Some("com.snowplowanalytics.snowplow"),
      event_name = Some("page_view"),
      event_format = Some("jsonschema"),
      event_version = Some("1-0-0"),
      derived_tstamp = Some(Instant.ofEpochMilli(0L))
    )

  val featureFlags = FeatureFlags(acceptInvalid = false, legacyEnrichmentOrder = false, tryBase64Decoding = false)
}
