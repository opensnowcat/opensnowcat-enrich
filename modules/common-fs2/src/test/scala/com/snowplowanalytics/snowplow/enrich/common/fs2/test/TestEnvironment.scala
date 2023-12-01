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
package com.snowplowanalytics.snowplow.enrich.common.fs2.test

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Paths

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import cats.Monad

import cats.effect.{Blocker, Concurrent, ContextShift, IO, Resource, Timer}
import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect.testing.specs2.CatsIO

import fs2.Stream

import io.circe.parser

import com.snowplowanalytics.iglu.client.resolver.registries.{Http4sRegistryLookup, Registry}

import com.snowplowanalytics.snowplow.analytics.scalasdk.Event

import com.snowplowanalytics.snowplow.badrows.BadRow

import com.snowplowanalytics.snowplow.enrich.common.SpecHelpers.adaptersSchemas
import com.snowplowanalytics.snowplow.enrich.common.adapters.AdapterRegistry
import com.snowplowanalytics.snowplow.enrich.common.enrichments.EnrichmentRegistry
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.EnrichmentConf
import com.snowplowanalytics.snowplow.enrich.common.utils.{BlockerF, ShiftExecution}

import com.snowplowanalytics.snowplow.enrich.common.fs2.{Assets, AttributedData, Enrich, EnrichSpec, Environment}
import com.snowplowanalytics.snowplow.enrich.common.fs2.Environment.{Enrichments, StreamsSettings}
import com.snowplowanalytics.snowplow.enrich.common.fs2.SpecHelpers.{createIgluClient, filesResource, ioClock}
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.{Concurrency, Telemetry}
import com.snowplowanalytics.snowplow.enrich.common.fs2.io.Clients
import org.http4s.client.{Client => Http4sClient}
import org.http4s.dsl.Http4sDsl

case class TestEnvironment[A](
  env: Environment[IO, A],
  counter: Ref[IO, Counter],
  good: IO[Vector[AttributedData[Array[Byte]]]],
  pii: IO[Vector[AttributedData[Array[Byte]]]],
  bad: IO[Vector[Array[Byte]]]
) {

  /**
   * Run all streams for 5 seconds and get produced events as a list
   * Assets and metrics streams are empty by default, can be enabled
   * by updating default [[Environment]]
   * If assets stream is enabled, first events get enriched with assets
   * downloaded by [[Assets.State.make]], not by [[Assets.run]]
   * @param updateEnv function to update an environment created by
   *                  [[TestEnvironment.make]]
   */
  def run(
    updateEnv: Environment[IO, A] => Environment[IO, A] = identity
  )(
    implicit C: Concurrent[IO],
    CS: ContextShift[IO],
    T: Timer[IO]
  ): IO[(Vector[BadRow], Vector[Event], Vector[Event])] = {
    val updatedEnv = updateEnv(env)
    implicit val client: Http4sClient[IO] = updatedEnv.httpClient
    val stream = Enrich
      .run[IO, A](updatedEnv)
      .merge(
        Assets.run[IO, A](updatedEnv.blocker,
                          updatedEnv.shifter,
                          updatedEnv.semaphore,
                          updatedEnv.assetsUpdatePeriod,
                          updatedEnv.assetsState,
                          updatedEnv.enrichments
        )
      )
    for {
      _ <- stream.haltAfter(5.seconds).compile.drain
      goodVec <- good
      piiVec <- pii
      badVec <- bad
    } yield (badVec.map(TestEnvironment.parseBad(_)),
             piiVec.flatMap(p => EnrichSpec.normalize(new String(p.data, UTF_8)).toOption),
             goodVec.flatMap(g => EnrichSpec.normalize(new String(g.data, UTF_8)).toOption)
    )
  }

}

object TestEnvironment extends CatsIO {

  val logger: Logger[IO] =
    Slf4jLogger.getLogger[IO]

  val enrichmentReg: EnrichmentRegistry[IO] =
    EnrichmentRegistry[IO]()
  val enrichments: Environment.Enrichments[IO] =
    Environment.Enrichments(enrichmentReg, Nil)

  val ioBlocker: Resource[IO, Blocker] = Blocker[IO]

  val embeddedRegistry = Registry.EmbeddedRegistry

  val adapterRegistry = new AdapterRegistry(adaptersSchemas = adaptersSchemas)

  val http4sClient: Http4sClient[IO] = Http4sClient[IO] { _ =>
    val dsl = new Http4sDsl[IO] {}; import dsl._
    Resource.eval(Ok(""))
  }

  /**
   * A dummy test environment without enrichment and with noop sinks and sources
   * One can replace stream and sinks via `.copy`
   */
  def make(source: Stream[IO, Array[Byte]], enrichments: List[EnrichmentConf] = Nil): Resource[IO, TestEnvironment[Array[Byte]]] =
    for {
      http <- Clients.mkHttp[IO](ec = ExecutionContext.global)
      blocker <- ioBlocker
      _ <- filesResource(blocker, enrichments.flatMap(_.filesToCache).map(p => Paths.get(p._2)))
      counter <- Resource.eval(Counter.make[IO])
      metrics = Counter.mkCounterMetrics[IO](counter)(Monad[IO], ioClock)
      aggregates <- Resource.eval(AggregatesSpec.init[IO])
      metadata = AggregatesSpec.metadata[IO](aggregates)
      clients = Clients.init[IO](http, Nil)
      sem <- Resource.eval(Semaphore[IO](1L))
      assetsState <- Resource.eval(Assets.State.make(blocker, sem, clients, enrichments.flatMap(_.filesToCache)))
      shifter <- ShiftExecution.ofSingleThread
      enrichmentsRef <- {
        implicit val client: Http4sClient[IO] = http
        Enrichments.make[IO](enrichments, BlockerF.ofBlocker(blocker), shifter)
      }
      goodRef <- Resource.eval(Ref.of[IO, Vector[AttributedData[Array[Byte]]]](Vector.empty))
      piiRef <- Resource.eval(Ref.of[IO, Vector[AttributedData[Array[Byte]]]](Vector.empty))
      badRef <- Resource.eval(Ref.of[IO, Vector[Array[Byte]]](Vector.empty))
      igluClient <- Resource.eval(createIgluClient(List(embeddedRegistry)))
      environment = Environment[IO, Array[Byte]](
                      igluClient,
                      Http4sRegistryLookup(http),
                      enrichmentsRef,
                      sem,
                      assetsState,
                      http,
                      Some(http),
                      blocker,
                      shifter,
                      source,
                      adapterRegistry,
                      g => goodRef.update(_ ++ g),
                      Some(p => piiRef.update(_ ++ p)),
                      b => badRef.update(_ ++ b),
                      _ => IO.unit,
                      identity,
                      None,
                      metrics,
                      metadata,
                      None,
                      _ => "test_good_partition_key",
                      _ => "test_pii_partition_key",
                      _ => Map.empty,
                      _ => Map.empty,
                      Telemetry(true, 1.minute, "POST", "foo.bar", 1234, true, None, None, None, None, None),
                      EnrichSpec.processor,
                      StreamsSettings(Concurrency(10000, 64), 1024 * 1024),
                      None,
                      None,
                      EnrichSpec.featureFlags
                    )
      _ <- Resource.eval(logger.info("TestEnvironment initialized"))
    } yield TestEnvironment(environment, counter, goodRef.get, piiRef.get, badRef.get)

  def parseBad(bytes: Array[Byte]): BadRow = {
    val badRowStr = new String(bytes, UTF_8)
    val parsed =
      parser
        .parse(badRowStr)
        .getOrElse(throw new RuntimeException(s"Error parsing bad row json: $badRowStr"))
    parsed
      .as[BadRow]
      .getOrElse(throw new RuntimeException(s"Error decoding bad row: $parsed"))
  }
}
