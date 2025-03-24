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
package com.snowplowanalytics.snowplow.enrich.common.fs2.io

import cats.effect.kernel.Async
import cats.effect.std.{Hotswap, Semaphore}
import cats.effect.{Concurrent, Ref, Resource, Sync}
import cats.implicits._
import com.snowplowanalytics.snowplow.enrich.common.fs2.ByteSink
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Output.{FileSystem => FileSystemConfig}

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Path, StandardOpenOption}

object FileSink {

  def fileSink[F[_]: Concurrent: Async](config: FileSystemConfig): Resource[F, ByteSink[F]] =
    config.maxBytes match {
      case Some(max) => rotatingFileSink(config.file, max)
      case None => singleFileSink(config.file)
    }

  /** Writes all events to a single file. Used when `maxBytes` is missing from configuration */
  def singleFileSink[F[_]: Concurrent: Async](path: Path): Resource[F, ByteSink[F]] =
    for {
      channel <- makeChannel(path)
      sem <- Resource.eval(Semaphore(1L))
    } yield { records =>
      sem.permit.use { _ =>
        Async[F].blocking {
          records.foreach { bytes =>
            channel.write(ByteBuffer.wrap(bytes))
            channel.write(ByteBuffer.wrap(Array('\n'.toByte)))
          }
        }.void
      }
    }

  /**
   * Opens a new file when the existing file exceeds `maxBytes`
   *  Each file has an integer suffix e.g. /path/to/good.0001
   */
  def rotatingFileSink[F[_]: Concurrent: Async](
    path: Path,
    maxBytes: Long
  ): Resource[F, ByteSink[F]] =
    for {
      (hs, first) <- Hotswap(makeFile(1, path))
      ref <- Resource.eval(Ref.of(first))
      sem <- Resource.eval(Semaphore(1L))
    } yield { records =>
      sem.permit.use { _ =>
        records.traverse_ { bytes =>
          for {
            state <- ref.get
            state <- maybeRotate(hs, path, state, maxBytes, bytes.size)
            state <- writeLine(state, bytes)
            _ <- ref.set(state)
          } yield ()
        }
      }
    }

  case class FileState(
    index: Int,
    channel: FileChannel,
    bytes: Int
  )

  private def makeChannel[F[_]: Async](path: Path): Resource[F, FileChannel] =
    Resource.fromAutoCloseable(Async[F].blocking {
      FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    })

  private def makeFile[F[_]: Async](
    index: Int,
    base: Path
  ): Resource[F, FileState] = {
    val path = base.resolveSibling(f"${base.getFileName}%s.$index%04d")
    makeChannel(path).map { fc =>
      FileState(index, fc, 0)
    }
  }

  private def writeLine[F[_]: Async](
    state: FileState,
    bytes: Array[Byte]
  ): F[FileState] =
    Async[F]
      .blocking {
        state.channel.write(ByteBuffer.wrap(bytes))
        state.channel.write(ByteBuffer.wrap(Array('\n'.toByte)))
      }
      .as(state.copy(bytes = state.bytes + bytes.length + 1))

  private def maybeRotate[F[_]: Async](
    hs: Hotswap[F, FileState],
    base: Path,
    state: FileState,
    maxBytes: Long,
    bytesToWrite: Int
  ): F[FileState] =
    if (state.bytes + bytesToWrite > maxBytes)
      hs.swap(makeFile(state.index + 1, base))
    else
      Sync[F].pure(state)

}
