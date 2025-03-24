/*
 * Copyright (c) 2021-2023 Snowplow Analytics Ltd. All rights reserved.
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

import cats.Parallel
import cats.effect._
import cats.implicits._
import com.snowplowanalytics.snowplow.badrows.Processor
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.{
  BackoffPolicy,
  BlobStorageClients,
  Cloud,
  Input,
  Monitoring,
  Output,
  RetryCheckpointing
}
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.{CliConfig, ParsedConfigs}
import com.snowplowanalytics.snowplow.enrich.common.fs2.io.Clients.Client
import com.snowplowanalytics.snowplow.enrich.common.fs2.io.{FileSink, Retries, Source}
import fs2.Stream
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.syntax.all._

import scala.concurrent.ExecutionContext

object Run {
  private implicit def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  def run[F[_]: Clock: Async: Parallel: Temporal, A](
    args: List[String],
    name: String,
    version: String,
    description: String,
    ec: ExecutionContext,
    updateCliConfig: (ExecutionContext, CliConfig) => F[CliConfig],
    mkSource: (ExecutionContext, Input, Monitoring) => Stream[F, A],
    mkSinkGood: (ExecutionContext, Output) => Resource[F, AttributedByteSink[F]],
    mkSinkPii: (ExecutionContext, Output) => Resource[F, AttributedByteSink[F]],
    mkSinkBad: (ExecutionContext, Output) => Resource[F, ByteSink[F]],
    checkpoint: List[A] => F[Unit],
    mkClients: BlobStorageClients => List[ExecutionContext => Resource[F, Client[F]]],
    getPayload: A => Array[Byte],
    maxRecordSize: Int,
    cloud: Option[Cloud],
    getRegion: => Option[String]
  ): F[ExitCode] =
    CliConfig.command(name, version, description).parse(args) match {
      case Right(cli) =>
        updateCliConfig(ec, cli).flatMap { cfg =>
          ParsedConfigs
            .parse[F](cfg)
            .fold(
              err =>
                Logger[F]
                  .error(s"CLI arguments valid but some of the configuration is not correct. Error: $err")
                  .as[ExitCode](ExitCode.Error),
              parsed =>
                for {
                  _ <- Logger[F].info(s"Initialising resources for $name $version")
                  processor = Processor(name, version)
                  file = parsed.configFile
                  sinkGood = initAttributedSink(ec, file.output.good, mkSinkGood)
                  sinkPii = file.output.pii.map(out => initAttributedSink(ec, out, mkSinkPii))
                  sinkBad = file.output.bad match {
                              case f: Output.FileSystem =>
                                FileSink.fileSink[F](f)
                              case _ =>
                                mkSinkBad(ec, file.output.bad)
                            }
                  clients = mkClients(file.blobStorage).map(mk => mk(ec)).sequence
                  exit <- file.input match {
                            case p: Input.FileSystem =>
                              val env = Environment
                                .make[F, Array[Byte]](
                                  ec,
                                  parsed,
                                  Source.filesystem[F](p.dir),
                                  sinkGood,
                                  sinkPii,
                                  sinkBad,
                                  clients,
                                  _ => Sync[F].unit,
                                  identity,
                                  processor,
                                  maxRecordSize,
                                  cloud,
                                  getRegion,
                                  file.featureFlags
                                )
                              runEnvironment[F, Array[Byte]](env)
                            case input =>
                              val checkpointing = input match {
                                case retrySettings: RetryCheckpointing =>
                                  withRetries(
                                    retrySettings.checkpointBackoff,
                                    "Checkpointing failed",
                                    checkpoint
                                  )
                                case _ =>
                                  checkpoint
                              }
                              val env = Environment
                                .make[F, A](
                                  ec,
                                  parsed,
                                  mkSource(ec, file.input, file.monitoring),
                                  sinkGood,
                                  sinkPii,
                                  sinkBad,
                                  clients,
                                  checkpointing,
                                  getPayload,
                                  processor,
                                  maxRecordSize,
                                  cloud,
                                  getRegion,
                                  file.featureFlags
                                )
                              runEnvironment[F, A](env)
                          }
                } yield exit
            )
            .flatten
        }

      case Left(error) =>
        Logger[F].error(s"CLI arguments are invalid. Error: $error") >> Sync[F].pure(ExitCode.Error)
    }

  private def initAttributedSink[F[_]: Async: Temporal](
    blocker: ExecutionContext,
    output: Output,
    mkSinkGood: (ExecutionContext, Output) => Resource[F, AttributedByteSink[F]]
  ): Resource[F, AttributedByteSink[F]] =
    output match {
      case f: Output.FileSystem =>
        FileSink.fileSink[F](f).map(sink => records => sink(records.map(_.data)))
      case _ =>
        mkSinkGood(blocker, output)
    }

  private def runEnvironment[F[_]: Async: Temporal: Parallel, A](
    environment: Resource[F, Environment[F, A]]
  ): F[ExitCode] =
    environment.use { env =>
      val enrich = Enrich.run[F, A](env)
      val updates = Assets.run[F, A](env.shifter, env.semaphore, env.assetsUpdatePeriod, env.assetsState, env.enrichments)
      val telemetry = Telemetry.run[F, A](env)
      val reporting = env.metrics.report
      val metadata = env.metadata.report
      val flow = enrich.merge(updates).merge(reporting).merge(telemetry).merge(metadata)
      flow.compile.drain.as(ExitCode.Success).recoverWith { case exception: Throwable =>
        Logger[F].error(s"An error happened") >>
          Sync[F].raiseError[ExitCode](exception)
      }
    }

  private def withRetries[F[_]: Temporal, A, B](
    config: BackoffPolicy,
    errorMessage: String,
    f: A => F[B]
  )(
    implicit logger: Logger[F]
  ): A => F[B] = { a =>
    val retryPolicy = Retries.fullJitter[F](config)

    f(a)
      .retryingOnAllErrors(
        policy = retryPolicy,
        onError = (exception, retryDetails) =>
          logger
            .error(exception)(s"$errorMessage (${retryDetails.retriesSoFar} retries)")
      )
  }
}
