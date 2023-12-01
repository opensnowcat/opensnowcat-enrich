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
import java.nio.file.{Path, Paths, StandardCopyOption}

import scala.concurrent.duration._
import scala.util.control.NonFatal

import cats.{Applicative, Parallel}
import cats.implicits._

import cats.effect.{Blocker, Concurrent, ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.effect.concurrent.{Ref, Semaphore}

import retry.{RetryDetails, RetryPolicies, RetryPolicy, retryingOnSomeErrors}

import fs2.Stream
import fs2.hash.md5
import fs2.io.file.{exists, move, readAll, tempFileResource, writeAll}

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import com.snowplowanalytics.snowplow.enrich.common.utils.{BlockerF, HttpClient, ShiftExecution}

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
     * @param blocker Thread pool for downloading and reading files.
     * @param sem Permit shared with the enriching, used while initializing the state.
     * @param clients Clients to download the URIS.
     * @param enrichments Configurations of the enrichments. Contains the list of assets.
     */
    def make[F[_]: ConcurrentEffect: Timer: ContextShift](
      blocker: Blocker,
      sem: Semaphore[F],
      clients: Clients[F],
      assets: List[Asset]
    ): F[State[F]] =
      for {
        _ <- sem.acquire
        map <- build[F](blocker, clients, assets)
        hashes <- Ref.of[F, Map[URI, Hash]](map)
        _ <- sem.release
      } yield State(hashes, clients)

    def build[F[_]: ConcurrentEffect: Timer: ContextShift](
      blocker: Blocker,
      clients: Clients[F],
      assets: List[Asset]
    ): F[Map[URI, Hash]] =
      for {
        _ <- Logger[F].info("Initializing (downloading) enrichments assets")
        curDir <- getCurDir
        hashOpts <- buildFromLocal(blocker, assets)
        hashes <- hashOpts.traverse {
                    case (uri, path, Some(hash)) =>
                      Logger[F].info(s"Asset from $uri is found on local system at $path").as(uri -> hash)
                    case (uri, path, None) =>
                      download[F](blocker, curDir, clients, (uri, path)).use { a =>
                        move(blocker, a.tpmPath, a.finalPath, List(StandardCopyOption.REPLACE_EXISTING)).as(uri -> a.hash)
                      }
                  }
      } yield hashes.toMap

    def buildFromLocal[F[_]: Sync: ContextShift](blocker: Blocker, assets: List[Asset]): F[List[(URI, String, Option[Hash])]] =
      assets.traverse { case (uri, path) => local[F](blocker, path).map(hash => (uri, path, hash)) }

    /** Checks if file already exists on filesystem. */
    def local[F[_]: Sync: ContextShift](blocker: Blocker, path: String): F[Option[Hash]] = {
      val fpath = Paths.get(path)
      exists(blocker, fpath).ifM(
        Hash.fromStream(readAll(fpath, blocker, 1024)).map(_.some),
        Sync[F].pure(none)
      )
    }
  }

  /** MD5 hash. */
  final case class Hash private (s: String) extends AnyVal

  object Hash {
    private[this] def fromBytes(bytes: Array[Byte]): Hash = {
      val bi = new java.math.BigInteger(1, bytes)
      Hash(String.format("%0" + (bytes.length << 1) + "x", bi))
    }

    def fromStream[F[_]: Sync](stream: Stream[F, Byte]): F[Hash] =
      stream.through(md5).compile.to(Array).map(fromBytes)
  }

  /** Pair of a tracked `URI` and destination path on local FS (`java.nio.file.Path` is not serializable). */
  type Asset = (URI, String)

  case class Downloaded(
    uri: URI,
    tpmPath: Path,
    finalPath: Path,
    hash: Hash
  )

  /** Initializes the [[updateStream]] if refresh period is specified. */
  def run[F[_]: ConcurrentEffect: ContextShift: HttpClient: Parallel: Timer, A](
    blocker: Blocker,
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
        } yield updateStream[F](blocker, shifter, sem, assetsState, enrichments, interval, assets)
        Stream.eval(init).flatten
      case None =>
        Stream.empty.covary[F]
    }

  /**
   * Creates an update stream that periodically checks if new versions of assets are available.
   * If that's the case, updates them locally for the enrichments and updates the state.
   */
  def updateStream[F[_]: ConcurrentEffect: ContextShift: Parallel: Timer: HttpClient](
    blocker: Blocker,
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
        curDir <- getCurDir
        currentHashes <- state.hashes.get
        downloaded = downloadAll(blocker, curDir, state.clients, assets)
        _ <- downloaded.use { files =>
               val newAssets = findUpdates(currentHashes, files)
               if (newAssets.isEmpty)
                 Logger[F].info("All the assets are still the same, no update")
               else
                 sem.withPermit {
                   update(blocker, shifter, state, enrichments, newAssets)
                 }
             }
      } yield ()
    }

  /**
   * Downloads all the assets, each into a temporary path.
   * @return For each URI the temporary path and the hash of the file is returned,
   *         as well as the asset path on disk.
   */
  def downloadAll[F[_]: ConcurrentEffect: ContextShift: Timer](
    blocker: Blocker,
    dir: Path,
    clients: Clients[F],
    assets: List[Asset]
  ): Resource[F, List[Downloaded]] =
    assets.traverse(download(blocker, dir, clients, _))

  def download[F[_]: ConcurrentEffect: ContextShift: Timer](
    blocker: Blocker,
    dir: Path,
    clients: Clients[F],
    asset: Asset
  ): Resource[F, Downloaded] =
    tempFileResource[F](blocker, dir).evalMap { tmpPath =>
      downloadAndHash(blocker, clients, asset._1, tmpPath)
        .map(hash => Downloaded(asset._1, tmpPath, Paths.get(asset._2), hash))
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
  def update[F[_]: ConcurrentEffect: ContextShift: HttpClient](
    blocker: Blocker,
    shifter: ShiftExecution[F],
    state: State[F],
    enrichments: Ref[F, Environment.Enrichments[F]],
    newAssets: List[Downloaded]
  ): F[Unit] =
    for {
      _ <- newAssets.traverse_ { a =>
             Logger[F].info(s"Remote ${a.uri} has changed, updating it locally") *>
               move(blocker, a.tpmPath, a.finalPath, List(StandardCopyOption.REPLACE_EXISTING))
           }

      _ <- Logger[F].info("Refreshing the state of assets")
      hashes <- state.hashes.get
      updatedHashes = hashes ++ newAssets.map(a => (a.uri, a.hash)).toMap
      _ <- state.hashes.set(updatedHashes)

      _ <- Logger[F].info("Reinitializing enrichments")
      old <- enrichments.get
      fresh <- old.reinitialize(BlockerF.ofBlocker(blocker), shifter)
      _ <- enrichments.set(fresh)
    } yield ()

  def getCurDir[F[_]: Sync]: F[Path] =
    Sync[F].delay(Paths.get("").toAbsolutePath)

  def downloadAndHash[F[_]: Concurrent: ContextShift: Timer](
    blocker: Blocker,
    clients: Clients[F],
    uri: URI,
    destination: Path
  ): F[Hash] = {
    val stream = clients.download(uri).observe(writeAll[F](destination, blocker))
    Logger[F].info(s"Downloading $uri") *> retryDownload(Hash.fromStream(stream))
  }

  def retryDownload[F[_]: Sync: Timer, A](download: F[A]): F[A] =
    retryingOnSomeErrors[A](retryPolicy[F], worthRetrying, onError[F])(download)

  def retryPolicy[F[_]: Applicative]: RetryPolicy[F] =
    RetryPolicies.fullJitter[F](1500.milliseconds).join(RetryPolicies.limitRetries[F](5))

  def worthRetrying(e: Throwable): Boolean =
    e match {
      case _: Clients.RetryableFailure => true
      case _: IllegalArgumentException => false
      case NonFatal(_) => false
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
