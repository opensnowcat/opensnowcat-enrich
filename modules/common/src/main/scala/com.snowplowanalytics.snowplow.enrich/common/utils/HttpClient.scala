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
package com.snowplowanalytics.snowplow.enrich.common.utils

import scala.util.control.NonFatal
import cats.{Applicative, Id}
import cats.effect.Sync
import cats.implicits._
import fs2.Stream
import org.http4s.client.{Client => Http4sClient}
import org.http4s.headers.Authorization
import org.http4s.{BasicCredentials, EmptyBody, EntityBody, Header, Headers, Method, Request, Status, Uri}
import scalaj.http._

trait HttpClient[F[_]] {
  def getResponse(
    uri: String,
    authUser: Option[String],
    authPassword: Option[String],
    body: Option[String],
    method: String,
    connectionTimeout: Option[Long],
    readTimeout: Option[Long]
  ): F[Either[Throwable, String]]
}

object HttpClient {
  def apply[F[_]](implicit ev: HttpClient[F]): HttpClient[F] = ev

  private[utils] def getHeaders(authUser: Option[String], authPassword: Option[String]): Headers = {
    val alwaysIncludedHeaders = List(Header("content-type", "application/json"), Header("accept", "*/*"))
    if (authUser.isDefined || authPassword.isDefined)
      Headers(Authorization(BasicCredentials(authUser.getOrElse(""), authPassword.getOrElse(""))) :: alwaysIncludedHeaders)
    else Headers(alwaysIncludedHeaders)
  }

  implicit def syncHttpClient[F[_]: Sync](implicit http4sClient: Http4sClient[F]): HttpClient[F] =
    new HttpClient[F] {

      /**
       * Only uri, method and body are used for syncHttpClient
       * Other parameters exist for compatibility with Id instance
       * and they aren't used here
       * Corresponding configurations come from http4s client configuration
       */
      override def getResponse(
        uri: String,
        authUser: Option[String],
        authPassword: Option[String],
        body: Option[String],
        method: String,
        connectionTimeout: Option[Long],
        readTimeout: Option[Long]
      ): F[Either[Throwable, String]] =
        Uri.fromString(uri) match {
          case Left(parseFailure) =>
            Applicative[F].pure(new IllegalArgumentException(s"uri [$uri] is not valid: ${parseFailure.sanitized}").asLeft[String])
          case Right(validUri) =>
            val request = Request[F](
              uri = validUri,
              method = Method.fromString(method).getOrElse(Method.GET),
              body = body.fold[EntityBody[F]](EmptyBody)(s => Stream.emits(s.getBytes)),
              headers = getHeaders(authUser, authPassword)
            )
            http4sClient
              .run(request)
              .use[F, Either[Throwable, String]] { response =>
                val body = response.bodyText.compile.string
                response.status.responseClass match {
                  case Status.Successful => body.map(_.asRight[Throwable])
                  case _ => Applicative[F].pure(new Exception(s"Request failed with status ${response.status.code}").asLeft[String])
                }
              }
              .handleError(_.asLeft[String])
        }
    }

  implicit val idHttpClient: HttpClient[Id] =
    new HttpClient[Id] {
      override def getResponse(
        uri: String,
        authUser: Option[String],
        authPassword: Option[String],
        body: Option[String],
        method: String,
        connectionTimeout: Option[Long],
        readTimeout: Option[Long]
      ): Id[Either[Throwable, String]] =
        getBody(
          buildRequest(
            uri,
            authUser,
            authPassword,
            body,
            method,
            connectionTimeout,
            readTimeout
          )
        )
    }

  // The defaults are from scalaj library
  val DEFAULT_CONNECTION_TIMEOUT_MS = 1000
  val DEFAULT_READ_TIMEOUT_MS = 5000

  /**
   * Blocking method to get body of HTTP response
   * @param request assembled request object
   * @return validated body of HTTP request
   */
  private def getBody(request: HttpRequest): Either[Throwable, String] =
    try {
      val res = request.asString
      if (res.isSuccess) res.body.asRight
      else new Exception(s"Request failed with status ${res.code} and body ${res.body}").asLeft
    } catch {
      case NonFatal(e) => e.asLeft
    }

  /**
   * Build HTTP request object
   * @param uri full URI to request
   * @param authUser optional username for basic auth
   * @param authPassword optional password for basic auth
   * @param body optional request body
   * @param method HTTP method
   * @param connectionTimeout connection timeout, if not set default is 1000ms
   * @param readTimeout read timeout, if not set default is 5000ms
   * @return HTTP request
   */
  def buildRequest(
    uri: String,
    authUser: Option[String],
    authPassword: Option[String],
    body: Option[String],
    method: String = "GET",
    connectionTimeout: Option[Long],
    readTimeout: Option[Long]
  ): HttpRequest = {
    val req: HttpRequest = Http(uri).method(method).maybeTimeout(connectionTimeout, readTimeout)
    req.maybeAuth(authUser, authPassword).maybePostData(body)
  }

  implicit class RichHttpRequest(request: HttpRequest) {

    def maybeAuth(user: Option[String], password: Option[String]): HttpRequest =
      if (user.isDefined || password.isDefined)
        request.auth(user.getOrElse(""), password.getOrElse(""))
      else request

    def maybeTimeout(connectionTimeout: Option[Long], readTimeout: Option[Long]): HttpRequest =
      (connectionTimeout, readTimeout) match {
        case (Some(ct), Some(rt)) => request.timeout(ct.toInt, rt.toInt)
        case (Some(ct), None) => request.timeout(ct.toInt, DEFAULT_READ_TIMEOUT_MS)
        case (None, Some(rt)) => request.timeout(DEFAULT_CONNECTION_TIMEOUT_MS, rt.toInt)
        case _ => request.timeout(DEFAULT_CONNECTION_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS)
      }

    def maybePostData(body: Option[String]): HttpRequest =
      body
        .map(data => request.postData(data).header("content-type", "application/json").header("accept", "*/*"))
        .getOrElse(request)
  }
}
