package com.snowplowanalytics.snowplow.enrich.common.fs2

import org.specs2.mutable.Specification
import _root_.io.circe.Json

import java.time.Instant

class EnrichUtilsSpec extends Specification {

  "EnrichUtils" can {
    "isoDateToMicrosSinceEpochOrSelf" should {
      "convert iso dates to micros" in {
        val someTime = Instant.now()
        val input = Json.fromString(someTime.toString)
        val expected = Json.fromLong(TimeUtils.microSecondsSinceEpoch(someTime))

        EnrichUtils.isoDateToMicrosSinceEpochOrSelf(input) mustEqual expected
      }

      "return json as is if not an iso date" in {
        val stringJ = Json.fromString("")
        val stringJ2 = Json.fromString("sds")
        val longJ = Json.fromLong(0)
        val boolJ = Json.fromBoolean(false)

        EnrichUtils.isoDateToMicrosSinceEpochOrSelf(stringJ) mustEqual stringJ
        EnrichUtils.isoDateToMicrosSinceEpochOrSelf(stringJ2) mustEqual stringJ2
        EnrichUtils.isoDateToMicrosSinceEpochOrSelf(longJ) mustEqual longJ
        EnrichUtils.isoDateToMicrosSinceEpochOrSelf(boolJ) mustEqual boolJ
      }
    }
  }
}
