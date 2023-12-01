/*
 * Copyright (c) 2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.kafka
package test

import scala.concurrent.duration._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import cats.effect.{Blocker, IO}
import cats.effect.concurrent.Ref
import fs2.Stream
import org.specs2.mutable.Specification
import cats.effect.testing.specs2.CatsIO
import com.snowplowanalytics.snowplow.analytics.scalasdk.Event
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Input.{Kafka => InKafka}
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Output.{Kafka => OutKafka}
import com.snowplowanalytics.snowplow.enrich.common.fs2.test.CollectorPayloadGen


class EnrichKafkaSpec extends Specification with CatsIO {

  sequential

  private implicit def logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  val collectorPayloadsStream = "it-enrich-kinesis-collector-payloads"
  val enrichedStream = "it-enrich-kinesis-enriched"
  val badRowsStream = "it-enrich-kinesis-bad"

  val nbGood = 100l
  val nbBad = 10l

  type AggregateGood = List[Event]
  type AggregateBad = List[String]
  case class Aggregates(good: AggregateGood, bad: AggregateBad)

  val kafkaPort = 9092
  val bootstrapServers = s"localhost:$kafkaPort"

  val consumerConf: Map[String, String] = Map(
      "group.id" -> "it-enrich",
      "auto.offset.reset" -> "earliest",
      "key.deserializer" -> "org.apache.kafka.common.serialization.StringDeserializer",
      "value.deserializer" -> "org.apache.kafka.common.serialization.ByteArrayDeserializer"
  )

  val producerConf: Map[String, String] = Map(
    "acks" -> "all"
  )

  def run(): IO[Aggregates] = {

    val resources =
      for {
        blocker <- Blocker[IO]
        sink <- Sink.init[IO](blocker, OutKafka(collectorPayloadsStream, bootstrapServers, "", Set.empty, producerConf))
      } yield sink

    resources.use { sink =>
      val generate =
        CollectorPayloadGen.generate[IO](nbGood, nbBad)
          .evalMap(events => sink(List(events)))
          .onComplete(fs2.Stream.eval(Logger[IO].info(s"Random data has been generated and sent to $collectorPayloadsStream")))

      def consume(refGood: Ref[IO, AggregateGood], refBad: Ref[IO, AggregateBad]): Stream[IO, Unit] =
        consumeGood(refGood).merge(consumeBad(refBad))

      def consumeGood(ref: Ref[IO, AggregateGood]): Stream[IO, Unit] =
        Source.init[IO](InKafka(enrichedStream, bootstrapServers, consumerConf)).map(_.record.value).evalMap(aggregateGood(_, ref))

      def consumeBad(ref: Ref[IO, AggregateBad]): Stream[IO, Unit] =
        Source.init[IO](InKafka(badRowsStream, bootstrapServers, consumerConf)).map(_.record.value).evalMap(aggregateBad(_, ref))

      def aggregateGood(r: Array[Byte], ref: Ref[IO, AggregateGood]): IO[Unit] =
        for {
          e <- IO(Event.parse(new String(r)).getOrElse(throw new RuntimeException("can't parse enriched event")))
          _ <- ref.update(updateAggregateGood(_, e))
        } yield ()

      def aggregateBad(r: Array[Byte], ref: Ref[IO, AggregateBad]): IO[Unit] = {
        for {
          br <- IO(new String(r))
          _ <- ref.update(updateAggregateBad(_, br))
        } yield ()
      }

      def updateAggregateGood(aggregate: AggregateGood, e: Event): AggregateGood =
        e :: aggregate

      def updateAggregateBad(aggregate: AggregateBad, br: String): AggregateBad =
        br :: aggregate

      for {
        refGood <- Ref.of[IO, AggregateGood](Nil)
        refBad <- Ref.of[IO, AggregateBad](Nil)
        _ <-
          generate
            .merge(consume(refGood, refBad))
            .interruptAfter(30.seconds)
            .attempt
            .compile
            .drain
        aggregateGood <- refGood.get
        aggregateBad <- refBad.get
      } yield Aggregates(aggregateGood, aggregateBad)
    }
  }

  val aggregates = run().unsafeRunSync()

  "enrich-kinesis" should {
    "emit the expected enriched events" in {
      aggregates.good.size must beEqualTo(nbGood)
    }

    "emit the expected bad rows events" in {
      aggregates.bad.size must beEqualTo(nbBad)
    }
  }
}
