package com.snowplowanalytics.snowplow.enrich.common.fs2.utils

import com.snowplowanalytics.snowplow.analytics.scalasdk.Event
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.CustomOutputFormat
import io.circe.Json

object JsonOutputUtils {

  def transformEventToJson(event: Event, outputFormat: CustomOutputFormat): Json =
    outputFormat match {
      case CustomOutputFormat.BigQueryJson => BigQueryEncodingUtils.transformEventToBigQueryJson(event)
      case CustomOutputFormat.FlattenedJson => event.toJson(lossy = true)
      case CustomOutputFormat.EventbridgeJson(_, _) => event.toJson(lossy = true)
    }

}
