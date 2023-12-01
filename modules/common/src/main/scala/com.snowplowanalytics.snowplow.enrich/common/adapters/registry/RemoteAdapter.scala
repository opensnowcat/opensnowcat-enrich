/*
 * Copyright (c) 2014-2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.common
package adapters
package registry

import cats.Monad
import cats.data.NonEmptyList
import cats.effect.Clock
import cats.syntax.either._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.validated._
import com.snowplowanalytics.iglu.client.IgluCirceClient
import com.snowplowanalytics.iglu.client.resolver.registries.RegistryLookup
import com.snowplowanalytics.snowplow.badrows._
import io.circe.Json
import io.circe.syntax._

import loaders.CollectorPayload
import utils.{HttpClient, JsonUtils}
import Adapter.Adapted

/**
 * An adapter for an enrichment that is handled by a remote webservice.
 * @param remoteUrl the url of the remote webservice, e.g. http://localhost/myEnrichment
 * @param connectionTimeout max duration of each connection attempt
 * @param readTimeout max duration of read wait time
 */
final case class RemoteAdapter(
  remoteUrl: String,
  connectionTimeout: Option[Long],
  readTimeout: Option[Long]
) extends Adapter {

  /**
   * POST the given payload to the remote webservice,
   * @param payload The CollectorPayload containing one or more raw events
   * @param client The Iglu client used for schema lookup and validation
   * @return a Validation boxing either a NEL of RawEvents on Success, or a NEL of Failure Strings
   */
  override def toRawEvents[F[_]: Monad: RegistryLookup: Clock: HttpClient](
    payload: CollectorPayload,
    client: IgluCirceClient[F]
  ): F[Adapted] =
    payload.body match {
      case Some(body) if body.nonEmpty =>
        val _ = client
        val json = Json.obj(
          "contentType" := payload.contentType,
          "queryString" := toMap(payload.querystring),
          "headers" := payload.context.headers,
          "body" := payload.body
        )
        HttpClient[F]
          .getResponse(remoteUrl, None, None, Some(json.noSpaces), "POST", connectionTimeout, readTimeout)
          .map(processResponse(payload, _).toValidatedNel)
      case _ =>
        val msg = s"empty body: not a valid remote adapter $remoteUrl payload"
        Monad[F].pure(
          FailureDetails.AdapterFailure.InputData("body", none, msg).invalidNel
        )
    }

  /**
   * [REMOTE_ADAPTER] prefix is used by BRA to filter adapter failures not caused by remote adapters
   */
  def processResponse(
    payload: CollectorPayload,
    response: Either[Throwable, String]
  ): Either[FailureDetails.AdapterFailure, NonEmptyList[RawEvent]] =
    for {
      res <- response
               .leftMap(t =>
                 FailureDetails.AdapterFailure.InputData(
                   "body",
                   none,
                   s"[REMOTE_ADAPTER] could not get response from remote adapter $remoteUrl: ${t.getMessage}"
                 )
               )
      json <- JsonUtils
                .extractJson(res)
                .leftMap(e => FailureDetails.AdapterFailure.NotJson("body", res.some, "[REMOTE_ADAPTER] " + e))
      events <- json.hcursor
                  .downField("events")
                  .as[List[Map[String, String]]]
                  .leftMap(e =>
                    FailureDetails.AdapterFailure.InputData(
                      "body",
                      res.some,
                      s"[REMOTE_ADAPTER] could not be decoded as a list of json objects: ${e.getMessage}"
                    )
                  )
      nonEmptyEvents <- events match {
                          case Nil =>
                            FailureDetails.AdapterFailure
                              .InputData("body", res.some, "[REMOTE_ADAPTER] empty list of events")
                              .asLeft
                          case h :: t => NonEmptyList.of(h, t: _*).asRight
                        }
      rawEvents = nonEmptyEvents.map { e =>
                    RawEvent(
                      api = payload.api,
                      parameters = e.map { case (k, v) => (k, Option(v)) },
                      contentType = payload.contentType,
                      source = payload.source,
                      context = payload.context
                    )
                  }
    } yield rawEvents
}
