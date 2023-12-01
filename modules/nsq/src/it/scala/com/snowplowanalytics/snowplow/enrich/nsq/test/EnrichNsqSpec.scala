/*
 * Copyright (c) 2023 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.nsq
package test

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.effect.testing.specs2.CatsIO

import org.specs2.mutable.Specification

import Utils._

class EnrichNsqSpec extends Specification with CatsIO {

  override protected val Timeout = 10.minutes

  "enrich-nsq" should {
    "emit the correct number of enriched events and bad rows" in {
      val nbGood = 100l
      val nbBad = 10l
      mkResources[IO].use {
        case (blocker, topology, sink) =>
          for {
            refGood <- Ref.of[IO, AggregateGood](Nil)
            refBad <- Ref.of[IO, AggregateBad](Nil)
            _ <-
              generateEvents(sink, nbGood, nbBad, topology)
                .merge(consume(blocker, refGood, refBad, topology))
                .interruptAfter(30.seconds)
                .attempt
                .compile
                .drain
            aggregateGood <- refGood.get
            aggregateBad <- refBad.get
          } yield {
            aggregateGood.size === nbGood
            aggregateBad.size === nbBad
          }
      }
    }
  }
}
