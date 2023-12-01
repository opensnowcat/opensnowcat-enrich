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
package com.snowplowanalytics.snowplow.enrich.eventbridge

import java.net.URI
import java.util.UUID

import scala.concurrent.duration._

import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.{BackoffPolicy, Input, MetricsReporters, Monitoring, Output}

object IntegrationTestConfig {

  val region = "eu-central-1"
  val endpoint = "localhost"

  def eventBridgeOutputStreamConfig(localstackPort: Int, stream: EventbridgeStream) = Output.Eventbridge(
    eventBusName = stream.eventBusName,
    eventBusSource = stream.eventBusSource,
    region = Some(region),
    backoffPolicy = BackoffPolicy(10.millis, 10.seconds, Some(10)),
    throttledBackoffPolicy = BackoffPolicy(10.millis, 10.seconds, Some(10)),
    recordLimit = 10,
    byteLimit = 100000,
    customEndpoint = Some(URI.create(getEndpoint(localstackPort))),
    collector = None,
    payload = None
  )

  def kinesisOutputStreamConfig(localstackPort: Int, streamName: String) = Output.Kinesis(
    streamName,
    Some(region),
    None,
    BackoffPolicy(10.millis, 10.seconds, Some(10)),
    BackoffPolicy(100.millis, 1.second, None),
    500,
    5242880,
    Some(URI.create(getEndpoint(localstackPort)))
  )

  def kinesisInputStreamConfig(localstackPort: Int, streamName: String) = Input.Kinesis(
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

  val monitoring = Monitoring(
    None,
    MetricsReporters(None, None, false)
  )

  private def getEndpoint(localstackPort: Int): String =
    s"http://$endpoint:$localstackPort"

  case class EventbridgeStream(
    eventBusName: String,
    eventBusSource: String
  )
  case class Streams(
    kinesisInput: String, // events are received on this kinesis stream
    eventbridgeGood: EventbridgeStream, // enriched events get routed to this event bridge stream
    eventbridgeBad: EventbridgeStream, // bad events get routed to this event bridge stream
    kinesisOutputGood: String, // eventbridge routes good events to this kinesis stream
    kinesisOutputBad: String // eventbridge routes bad events to this kinesis stream
  )

  def getStreams(uuid: String): Streams = Streams(
    kinesisInput = s"kinesis-input-$uuid",
    eventbridgeGood = EventbridgeStream(
      eventBusName = s"eventbridge-output-good-name-$uuid",
      eventBusSource = s"eventbridge-output-good-source-$uuid"),
    eventbridgeBad = EventbridgeStream(
      eventBusName = s"eventbridge-output-bad-name-$uuid",
      eventBusSource = s"eventbridge-output-bad-source-$uuid"),
    kinesisOutputGood = s"kinesis-output-good-$uuid",
    kinesisOutputBad = s"kinesis-output-bad-$uuid"
      )
}
