package com.snowplowanalytics.snowplow.enrich.common.fs2

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import scala.util.Try

object TimeUtils {

  def microSecondsSinceEpoch(i: Instant): Long =
    ChronoUnit.MICROS.between(Instant.EPOCH, i)

  def isIsoDate(s: String): Boolean = {
    Try(DateTimeFormatter.ISO_DATE_TIME.parse(s)).isSuccess
  }

  def isoDateToInstant(s: String): Instant = {
    Instant.parse(s)
  }

}
