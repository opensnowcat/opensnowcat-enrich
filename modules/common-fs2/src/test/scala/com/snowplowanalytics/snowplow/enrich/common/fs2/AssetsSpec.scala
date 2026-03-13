/*
 * Copyright (c) 2020-2023 Snowplow Analytics Ltd. All rights reserved.
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

import java.net.URI

import org.specs2.ScalaCheck
import org.specs2.mutable.Specification

import scala.concurrent.duration._

import fs2.Stream
import fs2.io.file.{Files, Path}

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global

import cats.effect.testing.specs2.CatsEffect

import com.snowplowanalytics.snowplow.enrich.common.utils.{HttpClient, ShiftExecution}

import com.snowplowanalytics.snowplow.enrich.common.fs2.io.Clients

import com.snowplowanalytics.snowplow.enrich.common.fs2.test._

class AssetsSpec extends Specification with CatsEffect with ScalaCheck {

  private val maxmind1Hash = "0fd4bf9af00cbad44d63d9ff9c37c6c7"
  private val maxmind2Hash = "49a8954ec059847562dfab9062a2c50f"

  private val maxmindFile = "maxmind"
  private val flakyFile = "flaky"

  /** List of local files that have to be deleted after every test */
  private val TestFiles = List(
    Path(maxmindFile),
    Path(flakyFile)
  )

  sequential

  "Assets.State.make" should {
    "download assets" in {
      val uri = URI.create("http://localhost:8080/maxmind/GeoIP2-City.mmdb")
      val filename = maxmindFile
      val path = Path(filename)

      val assetsInit =
        Stream
          .eval(
            SpecHelpers.refreshState(List(uri -> filename)).use(_.hashes.get.map(_.get(uri)))
          )
          .withHttp
          .haltAfter(1.second)
          .compile
          .toList

      SpecHelpers.filesResource(TestFiles).use { _ =>
        for {
          assetExistsBefore <- Files[IO].exists(path)
          hashes <- assetsInit
          assetExists <- Files[IO].exists(path)
        } yield {
          assetExistsBefore must beFalse
          hashes must containTheSameElementsAs(List(Some(Assets.Hash(maxmind1Hash))))
          assetExists must beTrue
        }
      }
    }
  }

  "downloadAndHash" should {
    "retry downloads" in {
      val uri = URI.create("http://localhost:8080/flaky")
      val path = Path(flakyFile)

      val resources = for {
        state <- SpecHelpers.refreshState(Nil)
        _ <- SpecHelpers.filesResource(TestFiles)
      } yield state

      Stream
        .resource(resources)
        .evalMap { state =>
          Assets.downloadAndHash(state.clients, uri, path)
        }
        .withHttp
        .haltAfter(5.second)
        .compile
        .toList
        .map(_ == List(Assets.Hash("eccbc87e4b5ce2fe28308fd9f2a7baf3"))) // hash of file with "3"
    }
  }

  "updateStream" should {
    "update an asset that has been updated after initialization" in {
      val uri = URI.create("http://localhost:8080/maxmind/GeoIP2-City.mmdb")
      val filename = "maxmind"
      Stream
        .resource(SpecHelpers.refreshState(List(uri -> filename)))
        .flatMap { state =>
          val resources =
            for {
              shiftExecution <- ShiftExecution.ofSingleThread[IO]
              sem <- Resource.eval(Semaphore[IO](1L))
              http4s <- Clients.mkHttp[IO]()
              http = HttpClient.fromHttp4sClient(http4s)
              enrichments <- Environment.Enrichments.make[IO](List(), SpecHelpers.blockingEC, shiftExecution, http)
              _ <- SpecHelpers.filesResource(TestFiles)
            } yield (shiftExecution, sem, enrichments)

          val update = Stream
            .resource(resources)
            .flatMap { case (shift, sem, enrichments) =>
              Assets.updateStream[IO](SpecHelpers.blockingEC, shift, sem, state, enrichments, 1.second, List(uri -> filename))
            }
            .haltAfter(2.second)

          val before =
            Stream
              .eval(state.hashes.get.map(_.get(uri)))
              .concurrently(update)

          val after = Stream.eval(state.hashes.get.map(_.get(uri)))
          before ++ update ++ after
        }
        .withHttp
        .haltAfter(3.second)
        .compile
        .toList
        .map(_ == List(Some(Assets.Hash(maxmind1Hash)), (), Some(Assets.Hash(maxmind2Hash))))
    }
  }

  "Hash.fromStream" should {
    "always create a valid MD5 hash" in {
      prop { (bytes: Array[Byte]) =>
        val input = Stream.emits(bytes).covary[IO]
        Assets.Hash
          .fromStream(input)
          .map { hash =>
            hash.s.matches("^[a-f0-9]{32}$") must beTrue
          }
          .unsafeRunSync()
      }
    }
  }
}
