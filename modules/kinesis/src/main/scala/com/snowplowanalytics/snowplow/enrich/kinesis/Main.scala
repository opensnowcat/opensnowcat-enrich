/*
 * Copyright (c) 2020-2021 Snowplow Analytics Ltd. All rights reserved.
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

import java.util.concurrent.{Executors, TimeUnit}

import scala.concurrent.ExecutionContext

import cats.effect.{ExitCode, IO, IOApp, Resource}

object Main extends IOApp {

  // Resource-managed blocking ExecutionContext for I/O operations
  private val executionContextResource: Resource[IO, ExecutionContext] =
    Resource
      .make(IO {
        val poolSize = math.max(2, Runtime.getRuntime.availableProcessors())
        Executors.newFixedThreadPool(poolSize)
      })(pool =>
        IO {
          pool.shutdown()
          pool.awaitTermination(10, TimeUnit.SECONDS)
          ()
        }
      )
      .map(ExecutionContext.fromExecutorService)

  def run(args: List[String]): IO[ExitCode] =
    executionContextResource.use { blockingEC =>
      KinesisRun.run[IO](args, blockingEC)
    }
}
