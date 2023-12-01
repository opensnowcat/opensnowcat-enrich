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
package com.snowplowanalytics.snowplow.enrich

import cats.effect.{Blocker, ConcurrentEffect, ContextShift}

import dev.profunktor.fs2rabbit.config.{Fs2RabbitConfig, Fs2RabbitNodeConfig}
import dev.profunktor.fs2rabbit.interpreter.RabbitClient

import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.RabbitMQConfig

package object rabbitmq {

  def mapConfig(raw: RabbitMQConfig): Fs2RabbitConfig =
    Fs2RabbitConfig(
      nodes = raw.nodes.map(node => Fs2RabbitNodeConfig(node.host, node.port)),
      username = Some(raw.username),
      password = Some(raw.password),
      virtualHost = raw.virtualHost,
      ssl = raw.ssl,
      connectionTimeout = raw.connectionTimeout,
      requeueOnNack = false, // nack is never used in the app
      requeueOnReject = false, // reject is never used in the app
      internalQueueSize = Some(raw.internalQueueSize),
      automaticRecovery = raw.automaticRecovery,
      requestedHeartbeat = raw.requestedHeartbeat
    )

  def createClient[F[_]: ConcurrentEffect: ContextShift](
    blocker: Blocker,
    config: Fs2RabbitConfig
  ): F[RabbitClient[F]] =
    RabbitClient[F](config, blocker)
}
