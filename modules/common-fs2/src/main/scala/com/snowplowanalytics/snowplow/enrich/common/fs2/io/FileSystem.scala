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

import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import scala.io.{Source => SSource}

import cats.data.EitherT

import cats.effect.Sync
import cats.implicits._

import fs2.Stream

import _root_.io.circe.Decoder
import _root_.io.circe.config.syntax._

import com.typesafe.config.{Config => TSConfig, ConfigFactory}

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object FileSystem {

  private implicit def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  def list[F[_]: Sync](dir: Path): Stream[F, Path] =
    for {
      paths <- Stream.eval(Sync[F].delay(Files.list(dir)))
      path <- Stream.fromIterator(paths.iterator().asScala, 1)
    } yield path

  def readJson[F[_]: Sync, A: Decoder](path: Path, fallbacks: TSConfig => TSConfig): EitherT[F, String, A] =
    Sync[F]
      .delay(SSource.fromFile(path.toFile).mkString)
      .attemptT
      .leftMap(e => s"Cannot read file: ${e.getMessage}")
      .subflatMap { text =>
        val either = for {
          tsConfig <- Either.catchNonFatal(ConfigFactory.parseString(text)).leftMap(_.getMessage)
          tsConfig <- Either.catchNonFatal(tsConfig.resolve()).leftMap(e => s"Can't resolve config: ${e.getMessage}")
          tsConfig <- Either.catchNonFatal(fallbacks(tsConfig)).leftMap(_.getMessage)
          parsed <- tsConfig.as[A].leftMap(_.show)
        } yield parsed
        either.leftMap(reason => s"Cannot parse file $path: $reason")
      }

  def readJsonDir[F[_]: Sync, A: Decoder](dir: Path): EitherT[F, String, List[A]] =
    list(dir).compile.toList.attemptT
      .leftMap(e => show"Cannot list ${dir.toAbsolutePath.toString} directory with JSON: ${e.getMessage}")
      .map(_.filter { path =>
        val asStr = path.toString
        asStr.endsWith(".json") || asStr.endsWith(".hocon")
      })
      .flatMap { paths =>
        EitherT.liftF[F, String, Unit](Logger[F].info(s"Files found in $dir: ${paths.mkString(", ")}")) *>
          paths.traverse(p => readJson[F, A](p, identity))
      }
}
