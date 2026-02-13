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

import cats.effect.{IO, Resource}
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Input
import fs2.{Pipe, Stream}
import io.circe.Json

import scala.concurrent.duration._

object utils {

  sealed trait OutputRow
  object OutputRow {
    // TODO: we should use Event, we need to find a way to parse the flattened events
    final case class Good(event: Json) extends OutputRow
    // TODO: we should use BadRow, we need to find a way to parse it
    final case class Bad(badRow: Json) extends OutputRow
  }

  def mkEnrichPipe(
    localstackPort: Int,
    uuid: String
  ): Resource[IO, Pipe[IO, Array[Byte], OutputRow]] = {
    val streams = IntegrationTestConfig.getStreams(uuid)
    for {
      kinesisRawSink <- com.snowplowanalytics.snowplow.enrich.kinesis.Sink
                          .init[IO](IntegrationTestConfig.kinesisOutputStreamConfig(localstackPort, streams.kinesisInput))
    } yield {
      val kinesisGoodOutput = asGood(
        outputStream(IntegrationTestConfig.kinesisInputStreamConfig(localstackPort, streams.kinesisOutputGood))
      )
      val kinesisBadOutput = asBad(
        outputStream(IntegrationTestConfig.kinesisInputStreamConfig(localstackPort, streams.kinesisOutputBad))
      )

      collectorPayloads =>
        kinesisGoodOutput
          .merge(kinesisBadOutput)
          .interruptAfter(3.minutes)
          .concurrently(collectorPayloads.evalMap(bytes => kinesisRawSink(List(bytes))))
    }
  }

  private def outputStream(config: Input.Kinesis): Stream[IO, Array[Byte]] =
    com.snowplowanalytics.snowplow.enrich.kinesis.Source
      .init[IO](config, IntegrationTestConfig.monitoring)
      .map(r => com.snowplowanalytics.snowplow.enrich.kinesis.KinesisRun.getPayload(r))

  private def asGood(source: Stream[IO, Array[Byte]]): Stream[IO, OutputRow.Good] =
    source.map { bytes =>
      val s = new String(bytes)
      // this is an eventbridge event, we need to extract the `detail` entry from it
      val parsed = io.circe.parser.parse(s) match {
        case Right(json) =>
          json.hcursor
            .downField("detail")
            .as[Json] match {
            case Right(r) => r
            case Left(e) => throw new RuntimeException(s"Can't parse enriched events from eventbridge: $e, json: $json")
          }
        case Left(e) => throw new RuntimeException(s"Can't parse enriched event [$s]. Error: $e")
      }
      OutputRow.Good(parsed)
    }

  private def asBad(source: Stream[IO, Array[Byte]]): Stream[IO, OutputRow.Bad] =
    source.map { bytes =>
      val s = new String(bytes)
      // this is an eventbridge event, we need to extract the `detail` entry from it
      val parsed = io.circe.parser.parse(s) match {
        case Right(json) =>
          json.hcursor
            .downField("detail")
            .as[io.circe.Json]
            .getOrElse(throw new RuntimeException(s"Can't parse bad row from eventbridge: $s"))

        case Left(e) => throw new RuntimeException(s"Can't parse bad row [$s]. Error: $e")
      }
      OutputRow.Bad(parsed)
    }

  def parseOutput(output: List[OutputRow], testName: String): (List[Json], List[Json]) = {
    val good = output.collect { case OutputRow.Good(e) => e }
    println(s"[$testName] Bad rows:")
    val bad = output.collect { case OutputRow.Bad(b) =>
      println(s"[$testName] ${b.toString()}")
      b
    }
    (good, bad)
  }
}
