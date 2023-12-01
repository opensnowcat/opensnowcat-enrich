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

import java.nio.ByteBuffer
import java.nio.file.{Path, StandardOpenOption}
import java.nio.channels.FileChannel

import cats.implicits._

import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync}
import cats.effect.concurrent.{Ref, Semaphore}
import fs2.Hotswap

import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Output.{FileSystem => FileSystemConfig}

import com.snowplowanalytics.snowplow.enrich.common.fs2.ByteSink

object FileSink {

  def fileSink[F[_]: Concurrent: ContextShift](config: FileSystemConfig, blocker: Blocker): Resource[F, ByteSink[F]] =
    config.maxBytes match {
      case Some(max) => rotatingFileSink(config.file, max, blocker)
      case None => singleFileSink(config.file, blocker)
    }

  /** Writes all events to a single file. Used when `maxBytes` is missing from configuration */
  def singleFileSink[F[_]: Concurrent: ContextShift](path: Path, blocker: Blocker): Resource[F, ByteSink[F]] =
    for {
      channel <- makeChannel(blocker, path)
      sem <- Resource.eval(Semaphore(1L))
    } yield { records =>
      sem.withPermit {
        blocker.delay {
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
  def rotatingFileSink[F[_]: Concurrent: ContextShift](
    path: Path,
    maxBytes: Long,
    blocker: Blocker
  ): Resource[F, ByteSink[F]] =
    for {
      (hs, first) <- Hotswap(makeFile(blocker, 1, path))
      ref <- Resource.eval(Ref.of(first))
      sem <- Resource.eval(Semaphore(1L))
    } yield { records =>
      sem.withPermit {
        records.traverse_ { bytes =>
          for {
            state <- ref.get
            state <- maybeRotate(blocker, hs, path, state, maxBytes, bytes.size)
            state <- writeLine(blocker, state, bytes)
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

  private def makeChannel[F[_]: Sync: ContextShift](blocker: Blocker, path: Path): Resource[F, FileChannel] =
    Resource.fromAutoCloseableBlocking(blocker) {
      Sync[F].delay(FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
    }

  private def makeFile[F[_]: Sync: ContextShift](
    blocker: Blocker,
    index: Int,
    base: Path
  ): Resource[F, FileState] = {
    val path = base.resolveSibling(f"${base.getFileName}%s.$index%04d")
    makeChannel(blocker, path).map { fc =>
      FileState(index, fc, 0)
    }
  }

  private def writeLine[F[_]: Sync: ContextShift](
    blocker: Blocker,
    state: FileState,
    bytes: Array[Byte]
  ): F[FileState] =
    blocker
      .delay {
        state.channel.write(ByteBuffer.wrap(bytes))
        state.channel.write(ByteBuffer.wrap(Array('\n'.toByte)))
      }
      .as(state.copy(bytes = state.bytes + bytes.length + 1))

  private def maybeRotate[F[_]: Sync: ContextShift](
    blocker: Blocker,
    hs: Hotswap[F, FileState],
    base: Path,
    state: FileState,
    maxBytes: Long,
    bytesToWrite: Int
  ): F[FileState] =
    if (state.bytes + bytesToWrite > maxBytes)
      hs.swap(makeFile(blocker, state.index + 1, base))
    else
      Sync[F].pure(state)

}
