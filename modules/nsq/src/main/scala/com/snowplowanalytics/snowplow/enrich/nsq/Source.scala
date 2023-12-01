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

import cats.syntax.all._

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, Resource, Sync}

import fs2.Stream

import fs2.concurrent.Queue

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import com.snowplowanalytics.client.nsq._
import com.snowplowanalytics.client.nsq.callbacks._
import com.snowplowanalytics.client.nsq.exceptions.NSQException
import com.snowplowanalytics.client.nsq.lookup.DefaultNSQLookup

import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Input

object Source {

  private implicit def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  def init[F[_]: ConcurrentEffect: ContextShift](
    blocker: Blocker,
    input: Input
  ): Stream[F, Record[F]] =
    input match {
      case config: Input.Nsq =>
        for {
          q <- Stream.eval(Queue.bounded[F, Either[Throwable, Record[F]]](config.maxBufferQueueSize))
          _ <- Stream.resource(startConsumer(blocker, q, config))
          msg <- q.dequeueChunk(config.maxBufferQueueSize).flatMap(m => Stream.fromEither[F](m))
        } yield msg
      case i =>
        Stream.raiseError[F](new IllegalArgumentException(s"Input $i is not NSQ"))
    }

  private def startConsumer[F[_]: Sync: ContextShift: ConcurrentEffect](
    blocker: Blocker,
    queue: Queue[F, Either[Throwable, Record[F]]],
    config: Input.Nsq
  ): Resource[F, NSQConsumer] =
    Resource.make(
      Sync[F].delay {
        val consumer = createConsumer(queue, config)
        consumer.start()
      }
    )(consumer =>
      blocker
        .delay(consumer.shutdown())
        .handleErrorWith(e => Logger[F].error(e)(s"Cannot terminate NSQ consumer"))
    )

  private def createConsumer[F[_]: Sync: ContextShift: ConcurrentEffect](
    queue: Queue[F, Either[Throwable, Record[F]]],
    config: Input.Nsq
  ): NSQConsumer = {
    val messageCallback = new NSQMessageCallback {
      override def message(message: NSQMessage): Unit = {
        val msgBytes = message.getMessage
        val enqueue = queue.enqueue1(Right(Record(msgBytes, Sync[F].delay(message.finished()))))
        ConcurrentEffect[F].toIO(enqueue).unsafeRunSync()
      }
    }
    val errorCallback = new NSQErrorCallback {
      override def error(e: NSQException): Unit = {
        val enqueue = queue.enqueue1(Left(e))
        ConcurrentEffect[F].toIO(enqueue).unsafeRunSync()
      }
    }
    val lookup = new DefaultNSQLookup
    lookup.addLookupAddress(config.lookupHost, config.lookupPort)
    new NSQConsumer(
      lookup,
      config.topic,
      config.channel,
      messageCallback,
      new NSQConfig(),
      errorCallback
    )
  }
}
