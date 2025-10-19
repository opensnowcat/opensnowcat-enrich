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

import cats.Parallel
import cats.effect.{Blocker, Concurrent, ConcurrentEffect, ContextShift, Resource, Timer}
import cats.implicits._
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Output
import com.snowplowanalytics.snowplow.enrich.common.fs2.{AttributedByteSink, AttributedData, ByteSink}
import fs2.kafka._
import io.circe.Json
import io.circe.parser.parse

import java.util.UUID

object Sink {

  def init[F[_]: ConcurrentEffect: ContextShift: Parallel: Timer](
    blocker: Blocker,
    output: Output
  ): Resource[F, ByteSink[F]] =
    for {
      sink <- initAttributed(blocker, output)
    } yield (records: List[Array[Byte]]) => sink(records.map(AttributedData(_, UUID.randomUUID().toString, Map.empty)))

  def initAttributed[F[_]: ConcurrentEffect: ContextShift: Parallel: Timer](
    blocker: Blocker,
    output: Output
  ): Resource[F, AttributedByteSink[F]] =
    output match {
      case k: Output.Kafka =>
        val mapping = k.mapping.getOrElse(Map.empty[String, String])
        mkProducer(blocker, k).map { producer => records =>
          records.parTraverse_ { record =>
            producer
              .produceOne_(toProducerRecord(k.topicName, record, mapping))
              .flatten
              .void
          }
        }
      case o => Resource.eval(Concurrent[F].raiseError(new IllegalArgumentException(s"Output $o is not Kafka")))
    }

  private def mkProducer[F[_]: ConcurrentEffect: ContextShift](
    blocker: Blocker,
    output: Output.Kafka
  ): Resource[F, KafkaProducer[F, String, Array[Byte]]] = {
    val producerSettings =
      ProducerSettings[F, String, Array[Byte]]
        .withBootstrapServers(output.bootstrapServers)
        .withProperties(output.producerConf)
        .withBlocker(blocker)
        .withProperties(
          ("key.serializer", "org.apache.kafka.common.serialization.StringSerializer"),
          ("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer")
        )

    KafkaProducer[F].resource(producerSettings)
  }

  private def toProducerRecord(
    topicName: String,
    record: AttributedData[Array[Byte]],
    mapping: Map[String, String]
  ): ProducerRecord[String, Array[Byte]] = {
    val mappedTopic = resolveTopicName(record.data, mapping)
    ProducerRecord(mappedTopic.getOrElse(topicName), record.partitionKey, record.data)
      .withHeaders(Headers.fromIterable(record.attributes.map(t => Header(t._1, t._2))))
  }

  private[kafka] def resolveTopicName(data: Array[Byte], mapping: Map[String, String]): Option[String] = {
    if (mapping.isEmpty) return None

    val rawEnrichedEvent = new String(data)

    if (!rawEnrichedEvent.startsWith("{")) {
      extractHostFromGoodEvent(rawEnrichedEvent).flatMap(mapping.get)
    } else {
      extractHostFromBadRow(rawEnrichedEvent).flatMap(mapping.get)
    }
  }

  private[kafka] def extractHostFromBadRow(message: String): Option[String] = {
    if (!message.contains("badrows")) return None

    parse(message).toOption.flatMap { json =>
      json.hcursor
        .downField("data")
        .downField("payload")
        .downField("headers")
        .as[List[String]]
        .toOption
        .flatMap { headers =>
          headers
            .find(_.startsWith("Host: "))
            .map(_.stripPrefix("Host: "))
        }
    }
  }

  private[kafka] def extractHostFromGoodEvent(message: String): Option[String] = {
    if (!message.contains("http_header")) return None

    val fields = message.split("\t", -1)
    if (fields.length < 123) return None

    val derivedContexts = fields(122)
    if (derivedContexts.isEmpty) return None

    parse(derivedContexts).toOption.flatMap { json =>
      json.hcursor
        .downField("data")
        .as[List[Json]]
        .toOption
        .flatMap { contexts =>
          contexts.collectFirst {
            case ctx if ctx.hcursor.downField("schema").as[String].toOption.exists(_.contains("http_header")) =>
              val dataCursor = ctx.hcursor.downField("data")
              val nameOpt = dataCursor.downField("name").as[String].toOption
              val valueOpt = dataCursor.downField("value").as[String].toOption
              
              (nameOpt, valueOpt) match {
                case (Some("Host"), Some(hostValue)) => Some(hostValue)
                case _ => None
              }
          }.flatten
        }
    }
  }
}
