package com.snowplowanalytics.snowplow.enrich.common.fs2

import org.specs2.mutable.Specification

import java.time.Instant
import java.time.temporal.ChronoUnit

class TimeUtilsSpec extends Specification {

  "TimeUtils" can {
    "isIsoDate" should {
      "return true for iso dates" in {
        val isoDate = Instant.now().toString
        TimeUtils.isIsoDate(isoDate) must beTrue
      }

      "return false for non iso dates" in {
        val nonIsoDate = Instant.now().toString.replaceAll("T", " ")

        TimeUtils.isIsoDate(nonIsoDate) must beFalse
      }
    }

    "microSecondsSinceEpoch" should {
      "return 0 for epoch" in {
        val epoch = Instant.EPOCH
        TimeUtils.microSecondsSinceEpoch(epoch) mustEqual 0
      }

      "return time passed in micros since epoch" in {
        val yearInMicros = 1L * 12 * 365 * 24 * 60 * 60 * 1000000
        val year1971 = Instant.EPOCH.plus(yearInMicros, ChronoUnit.MICROS)
        TimeUtils.microSecondsSinceEpoch(year1971) mustEqual yearInMicros
      }
    }
  }
}
