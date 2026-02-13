/*
 * Copyright (c) 2012-2023 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.common.utils

import cats.effect.kernel.Sync
import cats.implicits._
import org.http4s.client.{Client => Http4sClient}
import org.http4s.headers.{Authorization, `Content-Type`}
import org.http4s.{BasicCredentials, EmptyBody, EntityEncoder, Header, Headers, MediaType, Method, Request, Status, Uri}
import org.typelevel.ci.CIString

trait HttpClient[F[_]] {
  def getResponse(
    uri: String,
    authUser: Option[String],
    authPassword: Option[String],
    body: Option[String],
    method: String
  ): F[Either[Throwable, String]]
}

object HttpClient {

  private[utils] def getHeaders(authUser: Option[String], authPassword: Option[String]): Headers = {
    val alwaysIncludedHeaders = List(
      Header.Raw(CIString("accept"), "*/*")
    )
    if (authUser.isDefined || authPassword.isDefined)
      Headers(
        Authorization(BasicCredentials(authUser.getOrElse(""), authPassword.getOrElse(""))),
        alwaysIncludedHeaders
      )
    else Headers(alwaysIncludedHeaders)
  }

  def fromHttp4sClient[F[_]: Sync](http4sClient: Http4sClient[F]): HttpClient[F] =
    new HttpClient[F] {
      override def getResponse(
        uri: String,
        authUser: Option[String],
        authPassword: Option[String],
        body: Option[String],
        method: String
      ): F[Either[Throwable, String]] =
        Uri.fromString(uri) match {
          case Left(parseFailure) =>
            Sync[F].pure(new IllegalArgumentException(s"uri [$uri] is not valid: ${parseFailure.sanitized}").asLeft[String])
          case Right(validUri) =>
            val baseRequest = Request[F](
              uri = validUri,
              method = Method.fromString(method).getOrElse(Method.GET),
              body = EmptyBody,
              headers = getHeaders(authUser, authPassword)
            )
            val request = body.fold(baseRequest) { bodyString =>
              val encoder = EntityEncoder
                .stringEncoder[F]
                .withContentType(`Content-Type`(MediaType.application.json))
              baseRequest.withEntity(bodyString)(encoder)
            }
            http4sClient
              .run(request)
              .use[Either[Throwable, String]] { response =>
                val responseBody = response.bodyText.compile.string
                response.status.responseClass match {
                  case Status.Successful => responseBody.map(_.asRight[Throwable])
                  case _ => Sync[F].pure(new Exception(s"Request failed with status ${response.status.code}").asLeft[String])
                }
              }
              .handleError(_.asLeft[String])
        }
    }
}
