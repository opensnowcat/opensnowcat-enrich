/*
 * Copyright (c) 2022-2023 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.kinesis

import scala.concurrent.duration._

import cats.data.Validated

import cats.effect.{IO, Resource}

import cats.effect.testing.specs2.CatsEffect

import fs2.{Pipe, Stream}

import com.snowplowanalytics.snowplow.analytics.scalasdk.Event

import com.snowplowanalytics.snowplow.badrows.BadRow

import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Input
import com.snowplowanalytics.snowplow.enrich.common.fs2.test.Utils

object utils extends CatsEffect {

  sealed trait OutputRow
  object OutputRow {
    final case class Good(event: Event) extends OutputRow
    final case class Bad(badRow: BadRow) extends OutputRow
  }

  def mkEnrichPipe(
    localstackPort: Int,
    uuid: String
  ): Resource[IO, Pipe[IO, Array[Byte], OutputRow]] = {
    val streams = KinesisConfig.getStreams(uuid)
    for {
      rawSink <- Sink.init[IO](KinesisConfig.rawStreamConfig(localstackPort, streams.raw))
    } yield {
      val enriched = asGood(outputStream(KinesisConfig.enrichedStreamConfig(localstackPort, streams.enriched)))
      val bad = asBad(outputStream(KinesisConfig.badStreamConfig(localstackPort, streams.bad)))

      collectorPayloads =>
        enriched
          .merge(bad)
          .interruptAfter(3.minutes)
          .concurrently(collectorPayloads.evalMap(bytes => rawSink(List(bytes))))
    }
  }

  private def outputStream(config: Input.Kinesis): Stream[IO, Array[Byte]] =
    Source
      .init[IO](config, KinesisConfig.monitoring)
      .map(r => KinesisRun.getPayload(r))

  private def asGood(source: Stream[IO, Array[Byte]]): Stream[IO, OutputRow.Good] =
    source.map { bytes =>
      OutputRow.Good {
        val s = new String(bytes)
        Event.parse(s) match {
          case Validated.Valid(e) => e
          case Validated.Invalid(e) =>
            throw new RuntimeException(s"Can't parse enriched event [$s]. Error: $e")
        }
      }
    }

  private def asBad(source: Stream[IO, Array[Byte]]): Stream[IO, OutputRow.Bad] =
    source.map { bytes =>
      OutputRow.Bad {
        val s = new String(bytes)
        Utils.parseBadRow(s) match {
          case Right(br) => br
          case Left(e) =>
            throw new RuntimeException(s"Can't decode bad row $s. Error: $e")
        }
      }
    }

  def parseOutput(output: List[OutputRow], testName: String): (List[Event], List[BadRow]) = {
    val good = output.collect { case OutputRow.Good(e) => e }
    println(s"[$testName] Bad rows:")
    val bad = output.collect { case OutputRow.Bad(b) =>
      println(s"[$testName] ${b.compact}")
      b
    }
    (good, bad)
  }
}
