/*
 * Copyright (c) 2016-2022 Snowplow Analytics Ltd. All rights reserved.
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
import cats.syntax.option._
import cats.syntax.validated._
import com.snowplowanalytics.iglu.client.IgluCirceClient
import com.snowplowanalytics.iglu.client.resolver.registries.RegistryLookup
import com.snowplowanalytics.snowplow.badrows._
import io.circe._
import io.circe.parser._

import loaders.CollectorPayload
import utils.{HttpClient, JsonUtils => JU}

import Adapter.Adapted

/**
 * Transforms a collector payload which conforms to a known version of the StatusGator Tracking
 * webhook into raw events.
 */
case class MailgunAdapter(schemas: MailgunSchemas) extends Adapter {
  // Tracker version for an Mailgun Tracking webhook
  private val TrackerVersion = "com.mailgun-v1"

  // Expected content type for a request body
  private val ContentType = "application/json"

  // Schemas for reverse-engineering a Snowplow unstructured event
  private[registry] val EventSchemaMap = Map(
    "bounced" -> schemas.messageBouncedSchemaKey,
    "clicked" -> schemas.messageClickedSchemaKey,
    "complained" -> schemas.messageComplainedSchemaKey,
    "delivered" -> schemas.messageDeliveredSchemaKey,
    "dropped" -> schemas.messageDroppedSchemaKey,
    "opened" -> schemas.messageOpenedSchemaKey,
    "unsubscribed" -> schemas.recipientUnsubscribedSchemaKey
  )

  /**
   * A Mailgun Tracking payload contains one single event in the body of the payload, stored within
   * a HTTP encoded string.
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
      case (Some(body), _) if body.isEmpty =>
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
      case (_, Some(ct)) if ct != ContentType =>
        val msg = s"expected $ContentType"
        Monad[F].pure(
          FailureDetails.AdapterFailure
            .InputData("contentType", ct.some, msg)
            .invalidNel
        )
      case (Some(body), Some(_)) =>
        val _ = client
        val params = toMap(payload.querystring)
        parse(body) match {
          case Left(e) =>
            val msg = s"could not parse body: ${JU.stripInstanceEtc(e.getMessage).orNull}"
            Monad[F].pure(
              FailureDetails.AdapterFailure
                .InputData("body", body.some, msg)
                .invalidNel
            )
          case Right(bodyJson) =>
            Monad[F].pure(
              bodyJson.hcursor
                .downField("event-data")
                .downField("event")
                .focus
                .map { eventType =>
                  (for {
                    schemaUri <- lookupSchema(eventType.asString, EventSchemaMap)
                    event <- payloadBodyToEvent(bodyJson)
                    mEvent <- mutateMailgunEvent(event)
                  } yield NonEmptyList.one(
                    RawEvent(
                      api = payload.api,
                      parameters = toUnstructEventParams(
                        TrackerVersion,
                        params,
                        schemaUri,
                        cleanupJsonEventValues(
                          mEvent,
                          eventType.asString.map(("event", _)),
                          List("timestamp")
                        ),
                        "srv"
                      ),
                      contentType = payload.contentType,
                      source = payload.source,
                      context = payload.context
                    )
                  )).toValidatedNel
                }
                .getOrElse {
                  val msg = "no `event` parameter provided: cannot determine event type"
                  FailureDetails.AdapterFailure
                    .InputData("body", body.some, msg)
                    .invalidNel
                }
            )
        }
    }

  /**
   * Adds, removes and converts input fields to output fields
   * @param json parsed event fields as a JValue
   * @return The mutated event.
   */
  private def mutateMailgunEvent(json: Json): Either[FailureDetails.AdapterFailure, Json] = {
    val attachmentCountKey = "attachmentCount"
    val camelCase = camelize(json)
    camelCase.asObject match {
      case Some(obj) =>
        val withFilteredFields = obj
          .filterKeys(name => !(name == "bodyPlain" || name == attachmentCountKey))
        val attachmentCount = for {
          acJson <- obj(attachmentCountKey)
          acInt <- acJson.as[Int].toOption
        } yield acInt
        val finalJsonObject = attachmentCount match {
          case Some(ac) => withFilteredFields.add(attachmentCountKey, Json.fromInt(ac))
          case _ => withFilteredFields
        }
        Json.fromJsonObject(finalJsonObject).asRight
      case _ =>
        FailureDetails.AdapterFailure
          .InputData("body", json.noSpaces.some, "body is not a json object")
          .asLeft
    }
  }

  /**
   * Converts payload into an event
   * @param body Webhook Json request body
   */
  private def payloadBodyToEvent(body: Json): Either[FailureDetails.AdapterFailure, Json] = {
    val bodyMap = body.hcursor.downField("signature")
    (bodyMap.downField("timestamp").focus, bodyMap.downField("token").focus, bodyMap.downField("signature").focus) match {
      case (None, _, _) =>
        FailureDetails.AdapterFailure
          .InputData("timestamp", none, "missing 'timestamp'")
          .asLeft
      case (_, None, _) =>
        FailureDetails.AdapterFailure
          .InputData("token", none, "missing 'token'")
          .asLeft
      case (_, _, None) =>
        FailureDetails.AdapterFailure
          .InputData("signature", none, "missing 'signature'")
          .asLeft
      case (Some(timestamp), Some(_), Some(_)) =>
        body.hcursor
          .downField("event-data")
          .downField("timestamp")
          .withFocus(_ => timestamp)
          .up
          .focus
          .flatMap(json => bodyMap.focus.map(_.deepMerge(json)))
          .toRight(
            FailureDetails.AdapterFailure
              .InputData("event-data", none, "missing 'event-data'")
          )
    }
  }
}
