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

import java.net.URI
import java.nio.file.Paths

import org.specs2.ScalaCheck
import org.specs2.mutable.Specification

import scala.concurrent.duration._

import fs2.Stream
import fs2.io.file.exists

import cats.effect.{Blocker, IO, Resource}
import cats.effect.concurrent.Semaphore

import cats.effect.testing.specs2.CatsIO
import com.snowplowanalytics.snowplow.enrich.common.utils.{BlockerF, ShiftExecution}

import com.snowplowanalytics.snowplow.enrich.common.fs2.test._
import org.http4s.client.{Client => Http4sClient}

class AssetsSpec extends Specification with CatsIO with ScalaCheck {

  private val maxmind1Hash = "0fd4bf9af00cbad44d63d9ff9c37c6c7"
  private val maxmind2Hash = "49a8954ec059847562dfab9062a2c50f"

  private val maxmindFile = "maxmind"
  private val flakyFile = "flaky"

  /** List of local files that have to be deleted after every test */
  private val TestFiles = List(
    Paths.get(maxmindFile),
    Paths.get(flakyFile)
  )

  sequential

  "Assets.State.make" should {
    "download assets" in {
      val uri = URI.create("http://localhost:8080/maxmind/GeoIP2-City.mmdb")
      val filename = maxmindFile
      val path = Paths.get("", filename)

      val assetsInit =
        Stream
          .eval(
            SpecHelpers.refreshState(List(uri -> filename)).use(_.hashes.get.map(_.get(uri)))
          )
          .withHttp
          .haltAfter(1.second)
          .compile
          .toList
          .map(_ == List(Some(Assets.Hash(maxmind1Hash))))

      val resources =
        for {
          blocker <- Blocker[IO]
          files <- SpecHelpers.filesResource(blocker, TestFiles)
        } yield (blocker, files)

      resources.use {
        case (blocker, _) =>
          for {
            assetExistsBefore <- exists[IO](blocker, path)
            hash <- assetsInit
            assetExists <- exists[IO](blocker, path)
          } yield {
            assetExistsBefore must beFalse
            hash must beTrue
            assetExists must beTrue
          }
      }
    }
  }

  "downloadAndHash" should {
    "retry downloads" in {
      val uri = URI.create("http://localhost:8080/flaky")
      val path = Paths.get(flakyFile)

      val resources = for {
        blocker <- Blocker[IO]
        state <- SpecHelpers.refreshState(Nil)
        _ <- SpecHelpers.filesResource(blocker, TestFiles)
      } yield (blocker, state)

      Stream
        .resource(resources)
        .evalMap {
          case (blocker, state) =>
            Assets.downloadAndHash(blocker, state.clients, uri, path)
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
      implicit val c: Http4sClient[IO] = TestEnvironment.http4sClient
      Stream
        .resource(SpecHelpers.refreshState(List(uri -> filename)))
        .flatMap { state =>
          val resources =
            for {
              blocker <- Blocker[IO]
              sem <- Resource.eval(Semaphore[IO](1L))
              enrichments <- Environment.Enrichments.make[IO](List(), BlockerF.noop, ShiftExecution.noop)
              _ <- SpecHelpers.filesResource(blocker, TestFiles)
            } yield (blocker, sem, enrichments)

          val update = Stream
            .resource(resources)
            .flatMap {
              case (blocker, sem, enrichments) =>
                Assets.updateStream[IO](blocker, ShiftExecution.noop, sem, state, enrichments, 1.second, List(uri -> filename))
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
        Assets.Hash.fromStream(input).map { hash =>
          hash.s.matches("^[a-f0-9]{32}$") must beTrue
        }
      }
    }
  }
}
