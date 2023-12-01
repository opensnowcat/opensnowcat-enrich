/*
 * Copyright (c) 2020-2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.pubsub

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import cats.Parallel
import cats.implicits._

import cats.effect.{Concurrent, ContextShift, Resource, Sync, Timer}

import com.permutive.pubsub.producer.PubsubProducer
import com.permutive.pubsub.producer.Model.{ProjectId, Topic}
import com.permutive.pubsub.producer.encoder.MessageEncoder
import com.permutive.pubsub.producer.grpc.{GooglePubsubProducer, PubsubProducerConfig}

import com.snowplowanalytics.snowplow.enrich.common.fs2.{AttributedByteSink, AttributedData, ByteSink}
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Output

import java.nio.charset.StandardCharsets

object Sink {

  private val maxAttributeBytesLength = 1024

  private implicit def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  def init[F[_]: Concurrent: ContextShift: Parallel: Timer](
    output: Output
  ): Resource[F, ByteSink[F]] =
    for {
      sink <- initAttributed(output)
    } yield (records: List[Array[Byte]]) => sink(records.map(AttributedData(_, "", Map.empty)))

  def initAttributed[F[_]: Concurrent: ContextShift: Parallel: Timer](
    output: Output
  ): Resource[F, AttributedByteSink[F]] =
    output match {
      case o: Output.PubSub =>
        pubsubSink[F, Array[Byte]](o)
      case o =>
        Resource.eval(Sync[F].raiseError(new IllegalArgumentException(s"Output $o is not PubSub")))
    }

  private def pubsubSink[F[_]: Concurrent: Parallel, A: MessageEncoder](
    output: Output.PubSub
  ): Resource[F, List[AttributedData[A]] => F[Unit]] = {
    val config = PubsubProducerConfig[F](
      batchSize = output.maxBatchSize,
      requestByteThreshold = Some(output.maxBatchBytes),
      delayThreshold = output.delayThreshold,
      onFailedTerminate = err => Logger[F].error(err)("PubSub sink termination error")
    )

    GooglePubsubProducer
      .of[F, A](ProjectId(output.project), Topic(output.name), config)
      .map(sinkBatch[F, A])
  }

  private def sinkBatch[F[_]: Concurrent: Parallel, A](producer: PubsubProducer[F, A])(records: List[AttributedData[A]]): F[Unit] =
    records.parTraverse_ { r =>
      val attributes = dropOversizedAttributes(r)
      producer.produce(r.data, attributes)
    }.void

  private def dropOversizedAttributes[A](r: AttributedData[A]): Map[String, String] =
    r.attributes.filter {
      case (_, value) =>
        value.getBytes(StandardCharsets.UTF_8).length <= maxAttributeBytesLength
    }
}
