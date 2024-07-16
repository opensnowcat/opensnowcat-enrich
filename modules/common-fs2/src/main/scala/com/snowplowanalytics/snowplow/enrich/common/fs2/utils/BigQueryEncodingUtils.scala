package com.snowplowanalytics.snowplow.enrich.common.fs2.utils

import _root_.io.circe.syntax._
import _root_.io.circe.{Json, JsonObject}
import com.snowplowanalytics.snowplow.analytics.scalasdk.Event

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.util.Try

object BigQueryEncodingUtils {

  /**
   * BigQuery Storage Write API expects timestamps to be encoded as numeric
   * and having the number of microseconds since the Unix epoch
   * @param event snowplow event
   * @return json compatible with BQ storage write API
   */
  def transformEventToBigQueryJson(event: Event): Json = {
    val neededFields = event.atomic ++
      event.contexts.toShreddedJson ++
      event.derived_contexts.toShreddedJson ++
      event.unstruct_event.toShreddedJson.toMap ++
      event.geoLocation

    JsonObject
      .fromMap(BigQueryEncodingUtils.mapIsoDatesToMicros(neededFields))
      .asJson
  }

  private[utils] def mapIsoDatesToMicros(fields: Map[String, Json]): Map[String, Json] =
    fields.mapValues(BigQueryEncodingUtils.isoDateToMicrosSinceEpochOrSelf)

  private[utils] def isoDateToMicrosSinceEpochOrSelf(json: Json): Json = {
    val maybeTimestamp = for {
      stringJson <- json.asString
      timestamp <- isoDateToInstant(stringJson)
    } yield timestamp

    maybeTimestamp match {
      case Some(timestamp) =>
        val micros = microSecondsSinceEpoch(timestamp)
        Json.fromLong(micros)
      case _ => json
    }
  }

  private[utils] def isoDateToInstant(isoDate: String): Option[Instant] = {
    Try(Instant.parse(isoDate)).toOption
  }

  def microSecondsSinceEpoch(timestamp: Instant): Long =
    ChronoUnit.MICROS.between(Instant.EPOCH, timestamp)

}
