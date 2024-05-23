package com.snowplowanalytics.snowplow.enrich.common.fs2

import _root_.io.circe.Json
import _root_.io.circe.JsonObject
import _root_.io.circe.syntax._
import com.snowplowanalytics.snowplow.analytics.scalasdk.ParsingError

object EnrichUtils {

  def isoDateToMicrosSinceEpochOrSelf(v: Json): Json =
    v.asString match {
      case Some(mightBeIso) if TimeUtils.isIsoDate(mightBeIso) =>
        val time = TimeUtils.isoDateToInstant(mightBeIso)
        val micros = TimeUtils.microSecondsSinceEpoch(time)
        Json.fromLong(micros)
      case _ => v
    }

  def mapIsoDatesToMicros(fields: Map[String, Json]): Map[String, Json] =
    fields.mapValues(EnrichUtils.isoDateToMicrosSinceEpochOrSelf)

  def transformTsvToBigQueryJson(tsv: String): Either[ParsingError, Json] = {
    val event = com.snowplowanalytics.snowplow.analytics.scalasdk.Event.parse(tsv)
    val neededFields = event.map {e =>
      e.atomic ++ e.contexts.toShreddedJson ++ e.derived_contexts.toShreddedJson ++ e.unstruct_event.toShreddedJson.toMap ++ e.geoLocation
    }
    neededFields.map {f =>
      JsonObject
        .fromMap(EnrichUtils.mapIsoDatesToMicros(f))
        .asJson
    }.toEither
  }

  def transformTsvToFlattenedJson(tsv: String): Either[ParsingError, Json] = {
    com.snowplowanalytics.snowplow.analytics.scalasdk.Event
      .parse(tsv)
      .map(_.toJson(lossy = true))
      .toEither
  }

}
