/*
 * Copyright (c) 2021 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.common.fs2.io

import scala.concurrent.duration.DurationLong

import org.specs2.mutable.Specification

import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.MetricsReporters

class StatsDReporterSpec extends Specification {
  val TestConfig = MetricsReporters.StatsD("localhost", 8125, Map("tag1" -> "abc"), 1.second, Some("snowplow.test."))

  "StatsDeporter" should {
    "serialize metrics" in {
      val snapshot = Metrics.MetricSnapshot(Some(10000L), 10, 20, 30, 0, Some(40), Some(0), Some(0))

      val result = StatsDReporter.serializedMetrics(snapshot, TestConfig)

      result must contain(
        exactly(
          "snowplow.test.raw:10|c|#tag1:abc",
          "snowplow.test.good:20|c|#tag1:abc",
          "snowplow.test.bad:30|c|#tag1:abc",
          "snowplow.test.latency:10000|g|#tag1:abc",
          "snowplow.test.invalid_enriched:0|c|#tag1:abc",
          "snowplow.test.remote_adapters_success:40|c|#tag1:abc",
          "snowplow.test.remote_adapters_failure:0|c|#tag1:abc",
          "snowplow.test.remote_adapters_timeout:0|c|#tag1:abc"
        )
      )
    }

    "serialize metrics when latency is empty" in {
      val snapshot = Metrics.MetricSnapshot(None, 10, 20, 30, 40, Some(40), Some(0), Some(0))

      val result = StatsDReporter.serializedMetrics(snapshot, TestConfig)

      result must contain(
        exactly(
          "snowplow.test.raw:10|c|#tag1:abc",
          "snowplow.test.good:20|c|#tag1:abc",
          "snowplow.test.bad:30|c|#tag1:abc",
          "snowplow.test.invalid_enriched:40|c|#tag1:abc",
          "snowplow.test.remote_adapters_success:40|c|#tag1:abc",
          "snowplow.test.remote_adapters_failure:0|c|#tag1:abc",
          "snowplow.test.remote_adapters_timeout:0|c|#tag1:abc"
        )
      )
    }

    "serialize metrics when remote adapter metrics are empty" in {
      val snapshot = Metrics.MetricSnapshot(Some(10000L), 10, 20, 30, 40, None, None, None)

      val result = StatsDReporter.serializedMetrics(snapshot, TestConfig)

      result must contain(
        exactly(
          "snowplow.test.raw:10|c|#tag1:abc",
          "snowplow.test.good:20|c|#tag1:abc",
          "snowplow.test.bad:30|c|#tag1:abc",
          "snowplow.test.latency:10000|g|#tag1:abc",
          "snowplow.test.invalid_enriched:40|c|#tag1:abc"
        )
      )
    }
  }
}
