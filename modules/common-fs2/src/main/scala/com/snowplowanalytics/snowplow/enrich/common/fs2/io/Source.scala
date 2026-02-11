/*
 * Copyright (c) 2019-2021 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.common.fs2.io

import cats.effect.kernel.Async

import fs2.Stream
import fs2.io.file.{Files, Path}

object Source {

  def filesystem[F[_]: Async](
    path: Path
  ): Stream[F, Array[Byte]] =
    recursiveDirectoryStream(path)
      .evalMap { file =>
        Files
          .forAsync[F]
          .readAll(file)
          .compile
          .to(Array)
      }

  private def recursiveDirectoryStream[F[_]: Async](path: Path): Stream[F, Path] =
    for {
      subPath <- Files.forAsync[F].list(path)
      isDir <- Stream.eval(Files.forAsync[F].isDirectory(subPath))
      file <- if (isDir) recursiveDirectoryStream(subPath) else Stream.emit(subPath)
    } yield file
}
