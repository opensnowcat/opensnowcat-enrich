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
package com.snowplowanalytics.snowplow.enrich.common.enrichments

import cats.Id
import cats.effect.Clock
import com.snowplowanalytics.snowplow.enrich.common.enrichments.CachingEvaluatorSpec.{TestClock, TestContext}
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.CachingEvaluator
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.CachingEvaluator._
import io.circe.Json
import io.circe.literal.JsonStringContext
import org.specs2.mutable.Specification

import scala.concurrent.duration.TimeUnit

class CachingEvaluatorSpec extends Specification {

  private val successTtl = 5
  private val errorTtl = 2

  "Cached evaluation should work when" >> {
    "TTL is not exceeded, second call not evaluated" >> {
      "for success" in {
        val context = setupContext()

        val v1 = getValue(context, ifEvaluated = successful(result = json""" { "field": "value1" } """))

        context.addSeconds(4) // for success => 4 < 5

        val v2 = getValue(context, ifEvaluated = successful(result = json""" { "field": "value2" } """))

        v1 must beRight(json""" { "field": "value1" } """)
        v2 must beRight(json""" { "field": "value1" } """)
      }

      "for errors" in {
        val context = setupContext()

        val v1 = getValue(context, ifEvaluated = error(new RuntimeException("Some error1!")))

        context.addSeconds(1) // for error => 1 < 2

        val v2 = getValue(context, ifEvaluated = error(new RuntimeException("This second error should not be evaluated!")))

        v1.left.map(_.getMessage) must beLeft("Some error1!")
        v2.left.map(_.getMessage) must beLeft("Some error1!")
      }
    }

    "TTL is exceeded, second call is evaluated" >> {
      "1 call - success, 2 call - success => use new json" in {
        val context = setupContext()

        val v1 = getValue(context, ifEvaluated = successful(result = json""" { "field": "value1" } """))

        context.addSeconds(6) // for success => 6 > 5

        val v2 = getValue(context, ifEvaluated = successful(result = json""" { "field": "value2" } """))

        v1 must beRight(json""" { "field": "value1" } """)
        v2 must beRight(json""" { "field": "value2" } """)
      }

      "1 call - success, 2 call - error => fallback to previous success, still TTL for errors in force " in {
        val context = setupContext()

        val v1 = getValue(context, ifEvaluated = successful(result = json""" { "field": "value1" } """))

        context.addSeconds(6) // for success => 6 > 5

        val v2 = getValue(context, ifEvaluated = error(new RuntimeException("This second error should be evaluated but not returned!")))

        context.addSeconds(3) // for error => 3 > 2

        val v3 = getValue(context, ifEvaluated = successful(result = json""" { "field": "value2" } """))

        v1 must beRight(json""" { "field": "value1" } """)
        v2 must beRight(json""" { "field": "value1" } """)
        v3 must beRight(json""" { "field": "value2" } """)
      }

      "1 call - error, 2 call - error => use new error" in {
        val context = setupContext()

        val v1 = getValue(context, ifEvaluated = error(new RuntimeException("Some error1!")))

        context.addSeconds(3) // for error => 3 > 2

        val v2 = getValue(context, ifEvaluated = error(new RuntimeException("This second error should be evaluated!")))

        v1.left.map(_.getMessage) must beLeft("Some error1!")
        v2.left.map(_.getMessage) must beLeft("This second error should be evaluated!")
      }

      "1 call - error, 2 call - success => use new json" in {
        val context = setupContext()

        val v1 = getValue(context, ifEvaluated = error(new RuntimeException("Some error1!")))

        context.addSeconds(3) // for error => 3 > 2

        val v2 = getValue(context, ifEvaluated = successful(result = json""" { "field": "value2" } """))

        v1.left.map(_.getMessage) must beLeft("Some error1!")
        v2 must beRight(json""" { "field": "value2" } """)
      }
    }

  }

  private def getValue(context: TestContext, ifEvaluated: GetResult[Id, Json]): Either[Throwable, Json] = {
    implicit val clock: TestClock = context.clock
    context.evaluation.evaluateForKey("key", ifEvaluated)
  }

  private def setupContext(): TestContext =
    TestContext(
      new TestClock,
      CachingEvaluator.create[Id, String, Json](Config(size = 1, successTtl, errorTtl))
    )

  private def successful(result: Json): GetResult[Id, Json] = () => Right(result)
  private def error(ex: Throwable): GetResult[Id, Json] = () => Left(ex)

}

object CachingEvaluatorSpec {

  final case class TestContext(clock: TestClock, evaluation: CachingEvaluator[Id, String, Json]) {
    def addSeconds(value: Int): Unit =
      clock.secondsCounter += value
  }

  final class TestClock extends Clock[Id] {
    var secondsCounter: Long = 0

    override def realTime(unit: TimeUnit): Id[Long] = secondsCounter
    override def monotonic(unit: TimeUnit): Id[Long] = secondsCounter
  }
}
