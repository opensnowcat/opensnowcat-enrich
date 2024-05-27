package com.snowplowanalytics.snowplow.enrich.common.fs2.utils

import _root_.io.circe.syntax._
import _root_.io.circe.{Json, JsonObject}
import com.snowplowanalytics.snowplow.analytics.scalasdk.Event

import java.time.Instant
import java.time.format.DateTimeFormatter
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

  private[utils] def isoDateToMicrosSinceEpochOrSelf(json: Json): Json =
    json.asString match {
      case Some(mightBeIso) if isIsoDate(mightBeIso) =>
        val time = isoDateToInstant(mightBeIso)
        val micros = microSecondsSinceEpoch(time)
        Json.fromLong(micros)
      case _ => json
    }

  private[utils] def isIsoDate(maybeIsoDate: String): Boolean = {
    Try(DateTimeFormatter.ISO_DATE_TIME.parse(maybeIsoDate)).isSuccess
  }

  private[utils] def isoDateToInstant(isoDate: String): Instant = {
    Instant.parse(isoDate)
  }

  def microSecondsSinceEpoch(timestamp: Instant): Long =
    ChronoUnit.MICROS.between(Instant.EPOCH, timestamp)

}
