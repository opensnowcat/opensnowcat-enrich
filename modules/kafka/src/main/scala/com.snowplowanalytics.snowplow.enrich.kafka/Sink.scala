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
import com.snowplowanalytics.snowplow.analytics.scalasdk.Event
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

  private def resolveTopicName(data: Array[Byte], mapping: Map[String, String]): Option[String] = {
    val rawEnrichedEvent = new String(data)
    val hostOpt = Event
      .parse(rawEnrichedEvent)
      .toOption
      .flatMap { event =>
        val rawContexts = event.derived_contexts.data
        val headerContexts = rawContexts.filter(_.schema.toSchemaUri == "iglu:org.ietf/http_header/jsonschema/1-0-0")
        val contextData = headerContexts.map(_.data).flatMap(_.asObject)
        val hostValues = contextData
          .flatMap { obj =>
            for {
              name <- obj("name").flatMap(_.asString)
              value <- obj("value").flatMap(_.asString)
            } yield (name.toLowerCase, value)
          }
          .filter(_._1 == "host")
          .map(_._2)

        hostValues.headOption
      }
      .orElse {
        for {
          json <- parse(rawEnrichedEvent).toOption
          cursor = json.hcursor
          contexts <- cursor.downField("contexts_org_ietf_http_header_1").as[List[Json]].toOption
          hostValue <- contexts.collectFirst {
                         case obj if obj.hcursor.downField("name").as[String].toOption.exists(_.equalsIgnoreCase("host")) =>
                           obj.hcursor.downField("value").as[String].toOption
                       }.flatten
        } yield hostValue
      }

    hostOpt.flatMap(mapping.get)
  }
}
