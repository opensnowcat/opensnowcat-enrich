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
package com.snowplowanalytics.snowplow.enrich.eventbridge

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import com.snowplowanalytics.snowplow.enrich.common.fs2.test.CollectorPayloadGen
import com.snowplowanalytics.snowplow.enrich.eventbridge.enrichments._
import org.specs2.mutable.Specification
import org.specs2.specification.AfterAll

import java.util.UUID
import scala.concurrent.duration._

class EnrichEventbridgeSpec extends Specification with AfterAll with CatsEffect {

  override protected val Timeout = 10.minutes

  def afterAll: Unit = Containers.localstack.stop()

  "enrich-eventbridge" should {
    "be able to parse the minimal config" in {
      Containers
        .enrich(
          configPath = "config/config.eventbridge.minimal.hocon",
          testName = "minimal",
          needsLocalstack = false,
          enrichments = Nil
        )
        .use { e =>
          IO(e.getLogs must contain("Running Enrich"))
        }
    }

    "emit the correct number of enriched events and bad rows" in {
      import utils._

      val testName = "count"
      val nbGood = 100L
      val nbBad = 100L
      val uuid = UUID.randomUUID().toString

      val resources = for {
        _ <- Containers.enrich(
               configPath = "modules/eventbridge/src/it/resources/enrich/enrich-localstack.hocon",
               testName = "count",
               needsLocalstack = true,
               enrichments = Nil,
               uuid = uuid
             )
        enrichPipe <- mkEnrichPipe(Containers.localstackMappedPort, uuid)
      } yield enrichPipe

      val input = CollectorPayloadGen.generate[IO](nbGood, nbBad)

      resources.use { enrich =>
        for {
          // for some weird reason, the records don't get consumed until the second time calling enrich pipeline
          _ <- enrich(CollectorPayloadGen.generate[IO](0)).compile.toList
          output <- enrich(input).compile.toList
          (good, bad) = parseOutput(output, testName)
        } yield {
          println(s"${good.size} and ${bad.size}")
          good.size.toLong must beEqualTo(nbGood)
          bad.size.toLong must beEqualTo(nbBad)
        }
      }
    }

    "run the enrichments and attach their context" in {
      import utils._

      val testName = "enrichments"
      val nbGood = 100L
      val uuid = UUID.randomUUID().toString

      val enrichments = List(
        ApiRequest,
        Javascript,
        SqlQuery,
        Yauaa
      )

      val enrichmentsContexts = enrichments.map(_.outputSchema).map { schemaKey =>
        s"contexts_${schemaKey.name}_${schemaKey.vendor}_${schemaKey.version.model}".replace('.', '_')
      }

      val resources = for {
        _ <- Containers.mysqlServer
        _ <- Containers.httpServer
        _ <- Containers.enrich(
               configPath = "modules/eventbridge/src/it/resources/enrich/enrich-localstack.hocon",
               testName = "enrichments",
               needsLocalstack = true,
               enrichments = enrichments,
               uuid = uuid
             )
        enrichPipe <- mkEnrichPipe(Containers.localstackMappedPort, uuid)
      } yield enrichPipe

      val input = CollectorPayloadGen.generate[IO](nbGood)

      resources.use { enrich =>
        for {
          // for some weird reason, the records don't get consumed until the second time calling enrich pipeline
          _ <- enrich(CollectorPayloadGen.generate[IO](0)).compile.toList
          output <- enrich(input).compile.toList
          (good, bad) = parseOutput(output, testName)
        } yield {
          good.size.toLong must beEqualTo(nbGood)
          good.map { enriched =>
            enrichmentsContexts.map { context =>
              enriched.hcursor.downField(context).toOption must beSome
            }
          }
          bad.size.toLong must beEqualTo(0L)
        }
      }
    }

    "shutdown when it receives a SIGTERM" in {
      Containers
        .enrich(
          configPath = "modules/eventbridge/src/it/resources/enrich/enrich-localstack.hocon",
          testName = "stop",
          needsLocalstack = true,
          enrichments = Nil,
          waitLogMessage = "enrich.metrics"
        )
        .use { enrich =>
          for {
            _ <- IO(println("stop - Sending signal"))
            _ <- IO(enrich.getDockerClient().killContainerCmd(enrich.getContainerId()).withSignal("TERM").exec())
            _ <- Containers.waitUntilStopped(enrich)
          } yield {
            enrich.isRunning() must beFalse
            enrich.getLogs() must contain("Enrich stopped")
          }
        }
    }
  }
}
