/*
 * Copyright (c) 2012-2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.common.fs2.test

import cats.effect.{Blocker, IO, Resource}
import cats.effect.concurrent.Ref

import io.circe.literal._

import fs2.io.readInputStream

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import org.http4s.HttpRoutes
import org.http4s.Method.GET
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.dsl.io._
import org.http4s.syntax.all._

import cats.effect.testing.specs2.CatsIO

/**
 * Embedded HTTP Server for testing, mostly for assets refresh,
 * but can serve
 */
object HttpServer extends CatsIO {

  private val logger: Logger[IO] =
    Slf4jLogger.getLogger[IO]

  /**
   * Set of testing routes:
   * * Plain data
   * * Imitating slow connection
   * * Frequently updating resource
   * * Sometimes non-working resource
   *
   * @param counter mutable variable with counter updated on every request
   */
  def routes(counter: Ref[IO, Int]): HttpRoutes[IO] =
    HttpRoutes
      .of[IO] {
        case r @ GET -> Root / "counter" =>
          logger.debug(r.pathInfo) *> counter.updateAndGet(_ + 1).flatMap { i =>
            Ok(s"counter $i")
          }
        case r @ GET -> Root / "flaky" =>
          logger.debug(r.pathInfo) *> counter.update(_ + 1) *>
            counter.get.flatMap { i =>
              val s = i.toString
              if (i == 1 || i == 2) NotFound(s)
              else if (i == 3) Ok(s)
              else NotFound(s)
            }
        case GET -> Root / "maxmind" / "GeoIP2-City.mmdb" =>
          counter.updateAndGet(_ + 1).flatMap { i =>
            val is = readMaxMindDb(i)
            Ok(Blocker[IO].use(b => readInputStream[IO](is, 256, b).compile.to(Array)))
          }
        case GET -> Root / "iab" / file =>
          counter.updateAndGet(_ + 1).flatMap { i =>
            file match {
              case "include" => Ok("Mozilla/5.0 (Windows NT 6.1; WOW64; rv:12.0) Gecko/20100101 Firefox/12.0|1|1")
              case "exclude" => Ok("")
              case "ip" if i == 1 => Ok("175.16.199.0/32")
              case "ip" => Ok("175.1.1.0/32")
              case other =>
                println(s"Not Found ${other}")
                NotFound(other)
            }
          }
        case GET -> Root / "enrichment" / "api" / output =>
          counter.updateAndGet(_ + 1).flatMap { _ =>
            Ok(json"""{"output": $output}""".noSpaces)
          }
      }

  def resource: Resource[IO, Unit] =
    for {
      counter <- Resource.eval(Ref.of[IO, Int](0))
      _ <- BlazeServerBuilder[IO](concurrent.ExecutionContext.global)
             .bindHttp(8080)
             .withHttpApp(routes(counter).orNotFound)
             .withoutBanner
             .withoutSsl
             .resource
    } yield ()

  private def readMaxMindDb(req: Int) = {
    val path =
      if (req < 4) s"/assets-refresh/geoip2-city-$req.mmdb"
      else s"/assets-refresh/geoip2-city-3.mmdb"
    IO(getClass.getResourceAsStream(path))
  }
}
