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

import java.nio.file.NoSuchFileException
import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext

import cats.effect.{Clock, IO, Resource}
import cats.effect.std.Semaphore

import cats.implicits._

import fs2.io.file.{Files, Path}

import com.snowplowanalytics.iglu.client.{IgluCirceClient, Resolver}
import com.snowplowanalytics.iglu.client.resolver.registries.{JavaNetRegistryLookup, Registry, RegistryLookup}

import com.snowplowanalytics.snowplow.enrich.common.fs2.io.Clients

import cats.effect.testing.specs2.CatsEffect

object SpecHelpers extends CatsEffect {
  val StaticTime = 1599750938180L

  // RegistryLookup instance for IO
  implicit val registryLookup: RegistryLookup[IO] = JavaNetRegistryLookup.ioLookupInstance[IO]

  // Import adaptersSchemas from common.SpecHelpers
  val adaptersSchemas = com.snowplowanalytics.snowplow.enrich.common.SpecHelpers.adaptersSchemas

  implicit val staticIoClock: Clock[IO] = new Clock[IO] {
    override def applicative: cats.Applicative[IO] = cats.Applicative[IO]
    override def realTime: IO[scala.concurrent.duration.FiniteDuration] =
      IO.pure(scala.concurrent.duration.Duration(StaticTime, scala.concurrent.duration.MILLISECONDS))
    override def monotonic: IO[scala.concurrent.duration.FiniteDuration] =
      IO.pure(scala.concurrent.duration.Duration(StaticTime, scala.concurrent.duration.MILLISECONDS))
  }

  def refreshState(assets: List[Assets.Asset]): Resource[IO, Assets.State[IO]] =
    for {
      sem <- Resource.eval(Semaphore[IO](1L))
      http <- Clients.mkHttp[IO]()
      clients = Clients.init[IO](http, Nil)
      state <- Resource.eval(Assets.State.make[IO](sem, clients, assets))
    } yield state

  /** Clean-up predefined list of files */
  def filesCleanup(files: List[Path]): IO[Unit] =
    files.traverse_ { path =>
      Files[IO].deleteIfExists(path).recover { case _: NoSuchFileException =>
        false
      }
    }

  /** Make sure files don't exist before and after test starts */
  def filesResource(files: List[Path]): Resource[IO, Unit] =
    Resource.make(filesCleanup(files))(_ => filesCleanup(files))

  def createIgluClient(registries: List[Registry]): IO[IgluCirceClient[IO]] =
    IgluCirceClient.fromResolver[IO](Resolver[IO](registries, None), cacheSize = 0, maxJsonDepth = 40)

  val blockingEC = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool)
}
