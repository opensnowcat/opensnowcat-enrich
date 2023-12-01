/*
 * Copyright (c) 2022-2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.kinesis

import java.net.URI
import java.util.UUID

import scala.concurrent.duration._

import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.{BackoffPolicy, Input, MetricsReporters, Monitoring, Output}

object KinesisConfig {

  val region = "eu-central-1"
  val endpoint = "localhost"

  def enrichedStreamConfig(localstackPort: Int, streamName: String) = Input.Kinesis(
    UUID.randomUUID().toString,
    streamName,
    Some(region),
    Input.Kinesis.InitPosition.TrimHorizon,
    Input.Kinesis.Retrieval.Polling(1000),
    1000,
    BackoffPolicy(10.millis, 10.seconds, Some(10)),
    Some(URI.create(getEndpoint(localstackPort))),
    Some(URI.create(getEndpoint(localstackPort))),
    Some(URI.create(getEndpoint(localstackPort)))
  )

  def badStreamConfig(localstackPort: Int, streamName: String) = Input.Kinesis(
    UUID.randomUUID().toString,
    streamName,
    Some(region),
    Input.Kinesis.InitPosition.TrimHorizon,
    Input.Kinesis.Retrieval.Polling(1000),
    1000,
    BackoffPolicy(10.millis, 10.seconds, Some(10)),
    Some(URI.create(getEndpoint(localstackPort))),
    Some(URI.create(getEndpoint(localstackPort))),
    Some(URI.create(getEndpoint(localstackPort)))
  )

  def rawStreamConfig(localstackPort: Int, streamName: String) = Output.Kinesis(
    streamName,
    Some(region),
    None,
    BackoffPolicy(10.millis, 10.seconds, Some(10)),
    BackoffPolicy(100.millis, 1.second, None),
    500,
    5242880,
    Some(URI.create(getEndpoint(localstackPort)))
  )

  val monitoring = Monitoring(
    None,
    MetricsReporters(None, None, false)
  )

  private def getEndpoint(localstackPort: Int): String =
    s"http://$endpoint:$localstackPort"

  case class Streams(raw: String, enriched: String, bad: String)

  def getStreams(uuid: String): Streams =
    Streams(s"raw-$uuid", s"enriched-$uuid", s"bad-1-$uuid")
}
