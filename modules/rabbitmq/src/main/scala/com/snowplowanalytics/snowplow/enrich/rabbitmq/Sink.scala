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
package com.snowplowanalytics.snowplow.enrich.rabbitmq

import cats.implicits._
import cats.Parallel

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, Resource, Sync, Timer}

import dev.profunktor.fs2rabbit.config.Fs2RabbitConfig
import dev.profunktor.fs2rabbit.model._

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import retry.syntax.all._

import com.snowplowanalytics.snowplow.enrich.common.fs2.{AttributedByteSink, AttributedData, ByteSink}
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Output
import com.snowplowanalytics.snowplow.enrich.common.fs2.io.Retries

object Sink {

  private implicit def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  def init[F[_]: ConcurrentEffect: ContextShift: Parallel: Sync: Timer](
    blocker: Blocker,
    output: Output
  ): Resource[F, ByteSink[F]] =
    for {
      sink <- initAttributed(blocker, output)
    } yield records => sink(records.map(AttributedData(_, "", Map.empty)))

  def initAttributed[F[_]: ConcurrentEffect: ContextShift: Parallel: Sync: Timer](
    blocker: Blocker,
    output: Output
  ): Resource[F, AttributedByteSink[F]] =
    output match {
      case o: Output.RabbitMQ =>
        val mapped = mapConfig(o.cluster)
        initSink[F](blocker, o, mapped)
      case o =>
        Resource.eval(Sync[F].raiseError(new IllegalArgumentException(s"Output $o is not RabbitMQ")))
    }

  private def initSink[F[_]: ConcurrentEffect: ContextShift: Parallel: Timer](
    blocker: Blocker,
    rawConfig: Output.RabbitMQ,
    config: Fs2RabbitConfig
  ): Resource[F, AttributedByteSink[F]] =
    for {
      client <- Resource.eval(createClient[F](blocker, config))
      channel <- client.createConnectionChannel
      publisher <- Resource.eval {
                     implicit val ch = channel
                     val exchangeName = ExchangeName(rawConfig.exchange)
                     client.declareExchangePassive(exchangeName) *>
                       client.createPublisher[String](exchangeName, RoutingKey(rawConfig.routingKey))
                   }
      sink = (records: List[AttributedData[Array[Byte]]]) =>
               records
                 .map(_.data)
                 .parTraverse_ { bytes =>
                   publisher(new String(bytes))
                     .retryingOnAllErrors(
                       policy = Retries.fullJitter[F](rawConfig.backoffPolicy),
                       onError = (exception, retryDetails) =>
                         Logger[F]
                           .error(exception)(
                             s"Writing to ${rawConfig.exchange} errored (${retryDetails.retriesSoFar} retries from cats-retry)"
                           )
                     )
                 }
    } yield sink

}
