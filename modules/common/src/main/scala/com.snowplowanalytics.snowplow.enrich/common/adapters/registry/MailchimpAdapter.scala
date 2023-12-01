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
import cats.syntax.option._
import cats.syntax.validated._
import com.snowplowanalytics.iglu.client.IgluCirceClient
import com.snowplowanalytics.iglu.client.resolver.registries.RegistryLookup
import com.snowplowanalytics.snowplow.badrows._
import io.circe._
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat

import loaders.CollectorPayload
import utils.{ConversionUtils, HttpClient, JsonUtils => JU}

import Adapter.Adapted

/**
 * Transforms a collector payload which conforms to a known version of the Mailchimp Tracking
 * webhook into raw events.
 */
case class MailchimpAdapter(schemas: MailchimpSchemas) extends Adapter {
  // Expected content type for a request body
  private val ContentType = "application/x-www-form-urlencoded"

  // Tracker version for a Mailchimp Tracking webhook
  private val TrackerVersion = "com.mailchimp-v1"

  // Schemas for reverse-engineering a Snowplow unstructured event
  private[registry] val EventSchemaMap = Map(
    "subscribe" -> schemas.subscribeSchemaKey,
    "unsubscribe" -> schemas.unsubscribeSchemaKey,
    "campaign" -> schemas.campaignSendingStatusSchemaKey,
    "cleaned" -> schemas.cleanedEmailSchemaKey,
    "upemail" -> schemas.emailAddressChangeSchemaKey,
    "profile" -> schemas.profileUpdateSchemaKey
  )

  // Datetime format used by MailChimp (as we will need to massage)
  private val MailchimpDateTimeFormat =
    DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss").withZone(DateTimeZone.UTC)

  // Formatter Function to convert RawEventParameters into a merged Json Object
  private val MailchimpFormatter: FormatterFunc = { (parameters: RawEventParameters) =>
    mergeJsons(toJsons(parameters))
  }

  /**
   * Converts a CollectorPayload instance into raw events. An Mailchimp Tracking payload only
   * contains a single event.
   * We expect the name parameter to be 1 of 6 options otherwise we have an unsupported event type.
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
      case (_, Some(ct)) if ct != ContentType =>
        val failure = FailureDetails.AdapterFailure.InputData(
          "contentType",
          ct.some,
          s"expected $ContentType"
        )
        Monad[F].pure(failure.invalidNel)
      case (Some(body), _) =>
        val rawEvent = for {
          params <- ConversionUtils
                      .parseUrlEncodedForm(body)
                      .leftMap(e => FailureDetails.AdapterFailure.InputData("body", body.some, e))
          eventType <- params.get("type") match {
                         case Some(Some(typE)) => Right(typE)
                         case _ =>
                           val msg = "no `type` parameter provided: cannot determine event type"
                           Left(FailureDetails.AdapterFailure.InputData("body", body.some, msg))
                       }
          schema <- lookupSchema(eventType.some, EventSchemaMap)
          allParams = toMap(payload.querystring) ++ reformatParameters(params)
        } yield RawEvent(
          api = payload.api,
          parameters = toUnstructEventParams(
            TrackerVersion,
            allParams,
            schema,
            MailchimpFormatter,
            "srv"
          ),
          contentType = payload.contentType,
          source = payload.source,
          context = payload.context
        )
        Monad[F].pure(rawEvent.map(NonEmptyList.one).toValidatedNel)
    }

  /**
   * Generates a List of json fields from the raw event parameters.
   * @param parameters The Map of all the parameters for this raw event
   * @return a list of fields, where each field represents an entry from the incoming Map
   */
  private[registry] def toJsons(parameters: RawEventParameters): List[(String, Json)] =
    for {
      (k, v) <- parameters.toList.collect { case (k, Some(v)) => (k, v) }
    } yield toNestedJson(toKeys(k), v)

  /**
   * Returns a NEL of nested keys from a String representing a field from a URI-encoded POST body.
   * @param formKey The key String that (may) need to be split based on the supplied regexp
   * @return the key or keys as a NonEmptyList of Strings
   */
  private[registry] def toKeys(formKey: String): NonEmptyList[String] = {
    val keys = formKey.split("\\]?(\\[|\\])").toList
    NonEmptyList.of(keys.head, keys.tail: _*) // Safe only because split() never produces an empty Array
  }

  /**
   * Recursively generates a correct json field, working through the supplied NEL of keys.
   * @param keys The NEL of keys remaining to nest into our JObject
   * @param value The value we are going to finally insert when we run out of keys
   * @return a json field built from the list of key(s) and a value
   */
  private[registry] def toNestedJson(keys: NonEmptyList[String], value: String): (String, Json) =
    keys.toList match {
      case h1 :: h2 :: t => (h1, Json.obj(toNestedJson(NonEmptyList.of(h2, t: _*), value)))
      case h :: Nil => (h, Json.fromString(value))
      // unreachable but can't pattern match on NEL
      case _ => ("", Json.fromString(value))
    }

  /**
   * Merges a list of possibly overlapping nested json fields together, thus:
   * val a = ("data", ("nested", ("more-nested", ("str", "hi"))))
   * val b = ("data", ("nested", ("more-nested", ("num", 42))))
   * => {"data":{"nested":{"more-nested":{"str":"hi","num":42}}}}
   * @param jfields A (possibly-empty) list of json fields which need to be merged together
   * @return a fully merged json from the List of field provided, or json null if the List was empty
   */
  private[registry] def mergeJsons(jfields: List[(String, Json)]): Json =
    jfields match {
      case x :: xs => xs.foldLeft(Json.obj(x))(_ deepMerge Json.obj(_))
      case Nil => Json.Null
    }

  /**
   * Reformats the date-time stored in the fired_at parameter (if found) so that it can pass JSON
   * Schema date-time validation.
   * @param parameters The parameters to be checked for fixing
   * @return the event parameters, either with a fixed date-time for fired_at if that key was found,
   * or else the original parameters
   */
  private[registry] def reformatParameters(parameters: RawEventParameters): RawEventParameters =
    parameters.get("fired_at") match {
      case Some(Some(firedAt)) =>
        parameters.updated("fired_at", Some((JU.toJsonSchemaDateTime(firedAt, MailchimpDateTimeFormat))))
      case _ => parameters
    }
}
