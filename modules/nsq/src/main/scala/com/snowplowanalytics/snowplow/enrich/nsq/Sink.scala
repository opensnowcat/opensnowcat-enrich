/*
 * Copyright (c) 2023-2023 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.nsq

import scala.collection.JavaConverters._

import cats.effect.{Blocker, ContextShift, Resource, Sync, Timer}

import cats.syntax.all._

import retry.syntax.all._

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import com.snowplowanalytics.client.nsq.NSQProducer

import com.snowplowanalytics.snowplow.enrich.common.fs2.{AttributedByteSink, AttributedData, ByteSink}
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.{BackoffPolicy, Output}
import com.snowplowanalytics.snowplow.enrich.common.fs2.io.Retries

object Sink {

  private implicit def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  def init[F[_]: Sync: ContextShift: Timer](
    blocker: Blocker,
    output: Output
  ): Resource[F, ByteSink[F]] =
    for {
      sink <- initAttributed(blocker, output)
    } yield (records: List[Array[Byte]]) => sink(records.map(AttributedData(_, "", Map.empty)))

  def initAttributed[F[_]: Sync: ContextShift: Timer](
    blocker: Blocker,
    output: Output
  ): Resource[F, AttributedByteSink[F]] =
    output match {
      case config: Output.Nsq =>
        createNsqProducer(blocker, config)
          .map(p => sinkBatch[F](blocker, p, config.topic, config.backoffPolicy))
      case c =>
        Resource.eval(Sync[F].raiseError(new IllegalArgumentException(s"Output $c is not NSQ")))
    }

  private def createNsqProducer[F[_]: Sync: ContextShift](blocker: Blocker, config: Output.Nsq): Resource[F, NSQProducer] =
    Resource.make(
      Sync[F].delay {
        val producer = new NSQProducer()
        producer.addAddress(config.nsqdHost, config.nsqdPort)
        producer.start()
      }
    )(producer =>
      blocker
        .delay(producer.shutdown())
        .handleErrorWith(e => Logger[F].error(s"Cannot terminate NSQ producer ${e.getMessage}"))
    )

  private def sinkBatch[F[_]: Sync: ContextShift: Timer](
    blocker: Blocker,
    producer: NSQProducer,
    topic: String,
    backoffPolicy: BackoffPolicy
  )(
    records: List[AttributedData[Array[Byte]]]
  ): F[Unit] =
    blocker
      .delay {
        producer.produceMulti(topic, records.map(_.data).asJava)
      }
      .retryingOnAllErrors(
        policy = Retries.fullJitter[F](backoffPolicy),
        onError = (exception, retryDetails) =>
          Logger[F]
            .error(exception)(
              s"Writing to $topic errored (${retryDetails.retriesSoFar} retries from cats-retry)"
            )
      )
}
