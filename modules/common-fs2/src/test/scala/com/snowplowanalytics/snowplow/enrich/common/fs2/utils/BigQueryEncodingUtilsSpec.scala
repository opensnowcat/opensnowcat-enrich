package com.snowplowanalytics.snowplow.enrich.common.fs2.utils

import _root_.io.circe.Json
import org.specs2.mutable.Specification

import java.time.Instant
import java.time.temporal.ChronoUnit

class BigQueryEncodingUtilsSpec extends Specification {

  "BigQueryEncodingUtils" can {
    "isoDateToMicrosSinceEpochOrSelf" should {
      "convert iso dates to micros" in {
        val iso = "2024-05-26T00:05:44.041728Z"
        val someTime = Instant.parse(iso)
        val input = Json.fromString(someTime.toString)
        val expected = Json.fromLong(BigQueryEncodingUtils.microSecondsSinceEpoch(someTime))

        BigQueryEncodingUtils.isoDateToMicrosSinceEpochOrSelf(input) mustEqual expected
      }

      "return json as is if not an iso date" in {
        val stringJ = Json.fromString("")
        val stringJ2 = Json.fromString("sds")
        val longJ = Json.fromLong(0)
        val boolJ = Json.fromBoolean(false)

        BigQueryEncodingUtils.isoDateToMicrosSinceEpochOrSelf(stringJ) mustEqual stringJ
        BigQueryEncodingUtils.isoDateToMicrosSinceEpochOrSelf(stringJ2) mustEqual stringJ2
        BigQueryEncodingUtils.isoDateToMicrosSinceEpochOrSelf(longJ) mustEqual longJ
        BigQueryEncodingUtils.isoDateToMicrosSinceEpochOrSelf(boolJ) mustEqual boolJ
      }
    }

    "microSecondsSinceEpoch" should {
      "return 0 for epoch" in {
        val epoch = Instant.EPOCH
        BigQueryEncodingUtils.microSecondsSinceEpoch(epoch) mustEqual 0
      }

      "return time passed in micros since epoch" in {
        val yearInMicros = 1L * 12 * 365 * 24 * 60 * 60 * 1000000
        val year1971 = Instant.EPOCH.plus(yearInMicros, ChronoUnit.MICROS)
        BigQueryEncodingUtils.microSecondsSinceEpoch(year1971) mustEqual yearInMicros
      }
    }
  }
}
