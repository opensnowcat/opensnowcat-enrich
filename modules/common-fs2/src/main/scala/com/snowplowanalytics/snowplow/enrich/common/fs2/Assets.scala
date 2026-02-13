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

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.control.NonFatal

import cats.Applicative
import cats.implicits._

import cats.effect.kernel.{Async, Ref, Resource, Sync}
import cats.effect.std.Semaphore

import retry.{RetryDetails, RetryPolicies, RetryPolicy, Sleep, retryingOnSomeErrors}

import fs2.Stream
import fs2.hashing.{HashAlgorithm, Hashing}
import fs2.io.file.{CopyFlag, CopyFlags, Files, Path}

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import com.snowplowanalytics.snowplow.enrich.common.utils.ShiftExecution

import com.snowplowanalytics.snowplow.enrich.common.fs2.io.Clients

/** Code in charge of downloading and updating the assets used by enrichments (e.g. MaxMind/IAB DBs). */
object Assets {

  private implicit def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  /**
   * Contains information about downloaded content and the clients to download URIs.
   * @param hashes Hash map of URIs and their latest known state (hash).
   * @param clients Clients to download URIs.
   */
  final case class State[F[_]](
    hashes: Ref[F, Map[URI, Hash]],
    clients: Clients[F]
  )

  object State {

    /**
     * Initializes the assets state.
     * Tries to find them on local FS and download them if they're missing.
     * @param sem Permit shared with the enriching, used while initializing the state.
     * @param clients Clients to download the URIS.
     * @param enrichments Configurations of the enrichments. Contains the list of assets.
     */
    def make[F[_]: Async](
      sem: Semaphore[F],
      clients: Clients[F],
      assets: List[Asset]
    ): F[State[F]] =
      for {
        _ <- sem.acquire
        map <- build[F](clients, assets)
        hashes <- Ref.of[F, Map[URI, Hash]](map)
        _ <- sem.release
      } yield State(hashes, clients)

    def build[F[_]: Async](
      clients: Clients[F],
      assets: List[Asset]
    ): F[Map[URI, Hash]] =
      for {
        _ <- Logger[F].info("Initializing (downloading) enrichments assets")
        curDir <- Files.forAsync[F].currentWorkingDirectory
        hashOpts <- buildFromLocal(assets)
        hashes <- hashOpts.traverse {
                    case (uri, path, Some(hash)) =>
                      Logger[F].info(s"Asset from $uri is found on local system at $path").as(uri -> hash)
                    case (uri, path, None) =>
                      download[F](curDir, clients, (uri, path)).use { a =>
                        Files.forAsync[F].move(a.tpmPath, a.finalPath, CopyFlags(CopyFlag.ReplaceExisting)).as(uri -> a.hash)
                      }
                  }
      } yield hashes.toMap

    def buildFromLocal[F[_]: Async](assets: List[Asset]): F[List[(URI, String, Option[Hash])]] =
      assets.traverse { case (uri, path) => local[F](Path(path)).map(hash => (uri, path, hash)) }

    /** Checks if file already exists on filesystem. */
    def local[F[_]: Async](path: Path): F[Option[Hash]] =
      Files
        .forAsync[F]
        .exists(path)
        .ifM(
          Hash.fromStream(Files.forAsync[F].readAll(path)).map(_.some),
          Sync[F].pure(none)
        )
  }

  /** MD5 hash. */
  final case class Hash private (s: String) extends AnyVal

  object Hash {
    private[this] def fromBytes(bytes: Array[Byte]): Hash = {
      val bi = new java.math.BigInteger(1, bytes)
      Hash(String.format("%0" + (bytes.length << 1) + "x", bi))
    }

    def fromStream[F[_]: Sync](stream: Stream[F, Byte]): F[Hash] = {
      val hashing = Hashing.forSync[F]
      stream
        .through(hashing.hash(HashAlgorithm.MD5))
        .compile
        .lastOrError
        .map(digest => fromBytes(digest.bytes.toArray))
    }
  }

  /** Pair of a tracked `URI` and destination path on local FS (`fs2.io.file.Path` is not serializable). */
  type Asset = (URI, String)

  case class Downloaded(
    uri: URI,
    tpmPath: Path,
    finalPath: Path,
    hash: Hash
  )

  /** Initializes the [[updateStream]] if refresh period is specified. */
  def run[F[_]: Async, A](
    blockingEC: ExecutionContext,
    shifter: ShiftExecution[F],
    sem: Semaphore[F],
    updatePeriod: Option[FiniteDuration],
    assetsState: Assets.State[F],
    enrichments: Ref[F, Environment.Enrichments[F]]
  ): Stream[F, Unit] =
    updatePeriod match {
      case Some(interval) =>
        val init = for {
          _ <- Logger[F].info(show"Assets will be checked every $interval")
          assets <- enrichments.get.map(_.configs.flatMap(_.filesToCache))
        } yield updateStream[F](blockingEC, shifter, sem, assetsState, enrichments, interval, assets)
        Stream.eval(init).flatten
      case None =>
        Stream.empty.covary[F]
    }

  /**
   * Creates an update stream that periodically checks if new versions of assets are available.
   * If that's the case, updates them locally for the enrichments and updates the state.
   */
  def updateStream[F[_]: Async](
    blockingEC: ExecutionContext,
    shifter: ShiftExecution[F],
    sem: Semaphore[F],
    state: State[F],
    enrichments: Ref[F, Environment.Enrichments[F]],
    interval: FiniteDuration,
    assets: List[Asset]
  ): Stream[F, Unit] =
    Stream.fixedDelay[F](interval).evalMap { _ =>
      for {
        _ <- Logger[F].info(show"Checking if following assets have been updated: ${assets.map(_._1).mkString(", ")}")
        curDir <- Files.forAsync[F].currentWorkingDirectory
        currentHashes <- state.hashes.get
        downloaded = downloadAll(curDir, state.clients, assets)
        _ <- downloaded.use { files =>
               val newAssets = findUpdates(currentHashes, files)
               if (newAssets.isEmpty)
                 Logger[F].info("All the assets are still the same, no update")
               else
                 sem.permit.use { _ =>
                   update(blockingEC, shifter, state, enrichments, newAssets)
                 }
             }
      } yield ()
    }

  /**
   * Downloads all the assets, each into a temporary path.
   * @return For each URI the temporary path and the hash of the file is returned,
   *         as well as the asset path on disk.
   */
  def downloadAll[F[_]: Async](
    dir: Path,
    clients: Clients[F],
    assets: List[Asset]
  ): Resource[F, List[Downloaded]] =
    assets.traverse(download(dir, clients, _))

  def download[F[_]: Async](
    dir: Path,
    clients: Clients[F],
    asset: Asset
  ): Resource[F, Downloaded] =
    Files.forAsync[F].tempFile(Some(dir), "", ".tmp", None).evalMap { tmpPath =>
      downloadAndHash(clients, asset._1, tmpPath)
        .map(hash => Downloaded(asset._1, tmpPath, Path(asset._2), hash))
    }

  /**
   * Compares the hashes of downloaded assets with existing ones and keeps the different ones.
   * @return List of assets that have been updated since last download.
   */
  def findUpdates(
    currentHashes: Map[URI, Hash],
    downloaded: List[Downloaded]
  ): List[Downloaded] =
    downloaded
      .filterNot(a => currentHashes.get(a.uri).contains(a.hash))

  /**
   * Performs all the updates after new version of at least an asset is available:
   * 1. Replaces the existing file(s) on disk
   * 2. Updates the state of the assets with new hash(es)
   * 3. Updates the enrichments config
   */
  def update[F[_]: Async](
    blockingEC: ExecutionContext,
    shifter: ShiftExecution[F],
    state: State[F],
    enrichments: Ref[F, Environment.Enrichments[F]],
    newAssets: List[Downloaded]
  ): F[Unit] =
    for {
      _ <- newAssets.traverse_ { a =>
             Logger[F].info(s"Remote ${a.uri} has changed, updating it locally") *>
               Files.forAsync[F].move(a.tpmPath, a.finalPath, CopyFlags(CopyFlag.ReplaceExisting))
           }

      _ <- Logger[F].info("Refreshing the state of assets")
      hashes <- state.hashes.get
      updatedHashes = hashes ++ newAssets.map(a => (a.uri, a.hash)).toMap
      _ <- state.hashes.set(updatedHashes)

      _ <- Logger[F].info("Reinitializing enrichments")
      old <- enrichments.get
      fresh <- old.reinitialize(blockingEC, shifter)
      _ <- enrichments.set(fresh)
    } yield ()

  def downloadAndHash[F[_]: Async](
    clients: Clients[F],
    uri: URI,
    destination: Path
  ): F[Hash] = {
    val stream = clients.download(uri).observe(Files.forAsync[F].writeAll(destination))
    Logger[F].info(s"Downloading $uri") *> retryDownload(Hash.fromStream(stream))
  }

  def retryDownload[F[_]: Sleep: Sync, A](download: F[A]): F[A] =
    retryingOnSomeErrors[A](retryPolicy[F], worthRetrying[F], onError[F])(download)

  def retryPolicy[F[_]: Applicative]: RetryPolicy[F] =
    RetryPolicies.fullJitter[F](1500.milliseconds).join(RetryPolicies.limitRetries[F](5))

  def worthRetrying[F[_]: Applicative](e: Throwable): F[Boolean] =
    e match {
      case _: Clients.RetryableFailure => Applicative[F].pure(true)
      case _: IllegalArgumentException => Applicative[F].pure(false)
      case NonFatal(_) => Applicative[F].pure(false)
    }

  def onError[F[_]: Sync](error: Throwable, details: RetryDetails): F[Unit] =
    if (details.givingUp)
      Logger[F].error(show"Failed to download an asset after ${details.retriesSoFar}. ${error.getMessage}. Aborting the job")
    else if (details.retriesSoFar == 0)
      Logger[F].warn(show"Failed to download an asset. ${error.getMessage}. Keep retrying")
    else
      Logger[F].warn(
        show"Failed to download an asset after ${details.retriesSoFar} retries, " +
          show"waiting for ${details.cumulativeDelay.toMillis} ms. ${error.getMessage}. " +
          show"Keep retrying"
      )
}
