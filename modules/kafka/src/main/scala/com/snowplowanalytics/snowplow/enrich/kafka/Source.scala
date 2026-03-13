/*
 * Copyright (c) 2022 Snowplow Analytics Ltd. All rights reserved.
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

package com.snowplowanalytics.snowplow.enrich.kafka

import cats.effect.kernel.Async

import fs2.kafka.{CommittableConsumerRecord, ConsumerSettings, KafkaConsumer}
import fs2.Stream

import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Input

object Source {

  def init[F[_]: Async](
    input: Input
  ): Stream[F, CommittableConsumerRecord[F, String, Array[Byte]]] =
    input match {
      case k: Input.Kafka => kafka(k)
      case i => Stream.raiseError[F](new IllegalArgumentException(s"Input $i is not Kafka"))
    }

  def kafka[F[_]: Async](
    input: Input.Kafka
  ): Stream[F, CommittableConsumerRecord[F, String, Array[Byte]]] = {
    val consumerSettings =
      ConsumerSettings[F, String, Array[Byte]]
        .withBootstrapServers(input.bootstrapServers)
        .withProperties(input.consumerConf)
        .withEnableAutoCommit(false) // prevent enabling auto-commits by setting this after user-provided config
        .withProperties(
          ("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"),
          ("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer")
        )

    KafkaConsumer[F]
      .stream(consumerSettings)
      .subscribeTo(input.topicName)
      .records
  }
}
