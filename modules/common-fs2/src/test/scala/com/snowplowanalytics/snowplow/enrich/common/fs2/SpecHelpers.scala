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

import java.nio.file.{NoSuchFileException, Path}

import scala.concurrent.duration.TimeUnit
import scala.concurrent.ExecutionContext

import cats.effect.{Blocker, Clock, IO, Resource}

import cats.implicits._

import fs2.io.file.deleteIfExists

import com.snowplowanalytics.iglu.client.{IgluCirceClient, Resolver}
import com.snowplowanalytics.iglu.client.resolver.registries.Registry

import com.snowplowanalytics.snowplow.enrich.common.fs2.test._
import com.snowplowanalytics.snowplow.enrich.common.fs2.io.Clients

import cats.effect.testing.specs2.CatsIO
import cats.effect.concurrent.Semaphore

object SpecHelpers extends CatsIO {
  implicit val ioClock: Clock[IO] =
    Clock.create[IO]

  val StaticTime = 1599750938180L

  val staticIoClock: Clock[IO] =
    new Clock[IO] {
      def realTime(unit: TimeUnit): IO[Long] = IO.pure(StaticTime)
      def monotonic(unit: TimeUnit): IO[Long] = IO.pure(StaticTime)
    }

  def refreshState(assets: List[Assets.Asset]): Resource[IO, Assets.State[IO]] =
    for {
      b <- TestEnvironment.ioBlocker
      sem <- Resource.eval(Semaphore[IO](1L))
      http <- Clients.mkHttp[IO](ec = ExecutionContext.global)
      clients = Clients.init[IO](http, Nil)
      state <- Resource.eval(Assets.State.make[IO](b, sem, clients, assets))
    } yield state

  /** Clean-up predefined list of files */
  def filesCleanup(blocker: Blocker, files: List[Path]): IO[Unit] =
    files.traverse_ { path =>
      deleteIfExists[IO](blocker, path).recover {
        case _: NoSuchFileException => false
      }
    }

  /** Make sure files don't exist before and after test starts */
  def filesResource(blocker: Blocker, files: List[Path]): Resource[IO, Unit] =
    Resource.make(filesCleanup(blocker, files))(_ => filesCleanup(blocker, files))

  def createIgluClient(registries: List[Registry]): IO[IgluCirceClient[IO]] =
    IgluCirceClient.fromResolver[IO](Resolver(registries, None), cacheSize = 0)
}
