/*
 * Copyright (c) 2015-2022 Snowplow Analytics Ltd. All rights reserved.
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
import cats.data.ValidatedNel
import cats.effect.Clock
import cats.syntax.either._
import cats.syntax.option._
import cats.syntax.validated._
import com.snowplowanalytics.iglu.client.IgluCirceClient
import com.snowplowanalytics.iglu.client.resolver.registries.RegistryLookup
import com.snowplowanalytics.snowplow.badrows._

import loaders.CollectorPayload
import utils.{HttpClient, JsonUtils}
import Adapter.Adapted

/**
 * Transforms a collector payload which conforms to a known version of the Sendgrid Tracking webhook
 * into raw events.
 */
case class SendgridAdapter(schemas: SendgridSchemas) extends Adapter {
  // Expected content type for a request body
  private val ContentType = "application/json"

  // Tracker version for a Sendgrid Tracking webhook
  private val TrackerVersion = "com.sendgrid-v3"

  // Schemas for reverse-engineering a Snowplow self-describing event
  private[registry] val EventSchemaMap = Map(
    "processed" -> schemas.processedSchemaKey,
    "dropped" -> schemas.droppedSchemaKey,
    "delivered" -> schemas.deliveredSchemaKey,
    "deferred" -> schemas.deferredSchemaKey,
    "bounce" -> schemas.bounceSchemaKey,
    "open" -> schemas.openSchemaKey,
    "click" -> schemas.clickSchemaKey,
    "spamreport" -> schemas.spamreportSchemaKey,
    "unsubscribe" -> schemas.unsubscribeSchemaKey,
    "group_unsubscribe" -> schemas.groupUnsubscribeSchemaKey,
    "group_resubscribe" -> schemas.groupResubscribeSchemaKey
  )

  /**
   * Converts a CollectorPayload instance into raw events. A Sendgrid Tracking payload only contains
   * a single event. We expect the name parameter to be 1 of 6 options otherwise we have an
   * unsupported event type.
   * @param payload The CollectorPayload containing one or more raw events
   * @param client The Iglu client used for schema lookup and validation
   * @return a Validation boxing either a NEL of RawEvents on Success, or a NEL of Failure Strings
   */
  override def toRawEvents[F[_]: Monad: RegistryLookup: Clock: HttpClient](
    payload: CollectorPayload,
    client: IgluCirceClient[F]
  ): F[Adapted] =
    (payload.body, payload.contentType) match {
      case (None, _) =>
        val failure = FailureDetails.AdapterFailure.InputData(
          "body",
          none,
          "empty body: no events to process"
        )
        Monad[F].pure(failure.invalidNel)
      case (_, None) =>
        val msg = s"no content type: expected $ContentType"
        Monad[F].pure(
          FailureDetails.AdapterFailure.InputData("contentType", none, msg).invalidNel
        )
      case (_, Some(ct)) if !ct.contains(ContentType) =>
        val msg = s"expected $ContentType"
        Monad[F].pure(
          FailureDetails.AdapterFailure
            .InputData("contentType", ct.some, msg)
            .invalidNel
        )
      case (Some(body), _) =>
        val _ = client
        val events = payloadBodyToEvents(body, payload)
        Monad[F].pure(rawEventsListProcessor(events))
    }

  /**
   * Converts a payload into a list of validated events. Expects a valid json - returns a single
   * failure if one is not present
   * @param body json payload as POST'd by sendgrid
   * @param payload the rest of the payload details
   * @return a list of validated events, successes will be the corresponding raw events failures
   * will contain a non empty list of the reason(s) for the particular event failing
   */
  private def payloadBodyToEvents(body: String, payload: CollectorPayload): List[ValidatedNel[FailureDetails.AdapterFailure, RawEvent]] =
    JsonUtils.extractJson(body) match {
      case Right(json) =>
        json.asArray match {
          case Some(array) =>
            val queryString = toMap(payload.querystring)
            array.zipWithIndex
              .map {
                case (item, index) =>
                  val sgEventId: Option[String] = item.hcursor.downField("sg_event_id").as[String].toOption
                  (sgEventId, (item, index))
              }
              .toMap // removes duplicate keys based of sg_event_id
              .values
              .toList
              .map {
                case (item, index) =>
                  val eventType = item.hcursor.downField("event").as[String].toOption
                  lookupSchema(eventType, index, EventSchemaMap).map { schema =>
                    RawEvent(
                      api = payload.api,
                      parameters = toUnstructEventParams(
                        TrackerVersion,
                        queryString,
                        schema,
                        cleanupJsonEventValues(item, eventType.map(("event", _)), List("timestamp")),
                        "srv"
                      ),
                      contentType = payload.contentType,
                      source = payload.source,
                      context = payload.context
                    )
                  }.toValidatedNel
              }
          case None =>
            List(
              FailureDetails.AdapterFailure
                .InputData("body", body.some, "body is not a json array")
                .invalidNel
            )
        }
      case Left(e) =>
        List(FailureDetails.AdapterFailure.NotJson("body", body.some, e).invalidNel)
    }

}
