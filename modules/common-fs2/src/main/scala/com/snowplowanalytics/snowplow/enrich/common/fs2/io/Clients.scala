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
package com.snowplowanalytics.snowplow.enrich.common.fs2.io

import java.net.URI

import scala.concurrent.duration._

import org.typelevel.ci.CIString

import cats.effect.kernel.{Async, Resource}

import fs2.io.net.Network
import fs2.Stream

import org.http4s.{Headers, Request, Uri}
import org.http4s.client.defaults
import org.http4s.client.{Client => Http4sClient}
import org.http4s.client.middleware.Retry
import org.http4s.ember.client.EmberClientBuilder

import Clients._

case class Clients[F[_]: Async](clients: List[Client[F]]) {

  /** Download a URI as a stream of bytes, using the appropriate client */
  def download(uri: URI): Stream[F, Byte] =
    clients.find(_.canDownload(uri)) match {
      case Some(client) =>
        client.download(uri)
      case None =>
        Stream.raiseError[F](new IllegalStateException(s"No client initialized to download $uri"))
    }
}

object Clients {
  def init[F[_]: Async](httpClient: Http4sClient[F], others: List[Client[F]]): Clients[F] =
    Clients(wrapHttpClient(httpClient) :: others)

  def wrapHttpClient[F[_]: Async](client: Http4sClient[F]): Client[F] =
    new Client[F] {
      def canDownload(uri: URI): Boolean =
        // Since Azure Blob Storage urls' scheme are https as well and we want to fetch them with
        // their own client, we added second condition to not pick up those urls
        (uri.getScheme == "http" || uri.getScheme == "https") && !uri.toString.contains("core.windows.net")

      def download(uri: URI): Stream[F, Byte] = {
        val request = Request[F](uri = Uri.unsafeFromString(uri.toString))
        for {
          response <- client.stream(request)
          body <- if (response.status.isSuccess) response.body
                  else Stream.raiseError[F](HttpDownloadFailure(uri))
        } yield body
      }
    }

  def mkHttp[F[_]: Async](
    readTimeout: FiniteDuration = defaults.RequestTimeout,
    maxConnections: Int = 100 // http4s uses by default
  ): Resource[F, Http4sClient[F]] = {
    implicit val network: Network[F] = Network.forAsync[F]
    // idleConnectionTime must be > timeout to avoid warnings
    // We set it to 2x the request timeout to ensure connections are reused efficiently
    val idleTime = readTimeout * 2
    val builder = EmberClientBuilder
      .default[F]
      .withTimeout(readTimeout)
      .withIdleConnectionTime(idleTime)
      .withMaxTotal(maxConnections)
    builder.build.map(Retry[F](builder.retryPolicy, redactHeadersWhen))
  }

  private def redactHeadersWhen(header: CIString) =
    (Headers.SensitiveHeaders + CIString("apikey")).contains(header)

  trait RetryableFailure extends Throwable

  case class HttpDownloadFailure(uri: URI) extends RetryableFailure {
    override def getMessage: String = s"Cannot download $uri"
  }

  trait Client[F[_]] {
    def canDownload(uri: URI): Boolean
    def download(uri: URI): Stream[F, Byte]
  }
}
