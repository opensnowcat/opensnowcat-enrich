package com.snowplowanalytics.snowplow.enrich.common.fs2.utils

import com.snowplowanalytics.snowplow.analytics.scalasdk.Event
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.CustomOutputFormat
import io.circe.Json
import io.circe.syntax._

import java.nio.charset.StandardCharsets
import java.util.Base64

object JsonOutputUtils {

  def transformEventToJson(event: Event, outputFormat: CustomOutputFormat): Json =
    outputFormat match {
      case CustomOutputFormat.BigQueryJson => BigQueryEncodingUtils.transformEventToBigQueryJson(event)
      case CustomOutputFormat.FlattenedJson | CustomOutputFormat.EventbridgeJson(_, _) | CustomOutputFormat.SkinnyJson =>
        event.toJson(lossy = true)
    }

  def serializeEventbridgeEvent(
    tsv: String,
    event: Json,
    payload: Boolean,
    collector: Boolean
  ): Json = {
    lazy val base64TSV = computeBase64Tsv(tsv)
    lazy val host = computeCollectorValue(event)

    val attachments = List((payload, "payload", () => base64TSV.asJson), (collector, "collector", () => host.asJson))

    attachments.foldLeft(event) { case (current, (include, key, getValue)) =>
      if (include) current.deepMerge(Map(key -> getValue()).asJson)
      else current
    }
  }

  def serializeSkinnyJsonEvent(tsv: String, event: Json): Json = {
    lazy val base64TSV = computeBase64Tsv(tsv)
    lazy val host = computeCollectorValue(event)

    Json.obj(
      "payload" -> base64TSV.asJson,
      "collector" -> host.asJson
    )
  }

  private def computeBase64Tsv(tsv: String): String = Base64.getEncoder
    .encodeToString(tsv.getBytes(StandardCharsets.UTF_8))

  private def computeCollectorValue(event: Json): Option[String] =
    for {
      headers <- event.hcursor
                   .downField("contexts_org_ietf_http_header_1")
                   .as[List[Json]]
                   .toOption

      hostHeader <- headers.find { header =>
                      header.hcursor
                        .downField("name")
                        .as[String]
                        .toOption
                        .contains("Host")
                    }

      host <- hostHeader.hcursor
                .downField("value")
                .as[String]
                .toOption
    } yield host
}
