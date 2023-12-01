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
package com.snowplowanalytics.snowplow.enrich.common.enrichments.registry

import cats.Monad
import cats.effect.Clock
import cats.implicits._
import com.snowplowanalytics.lrumap.{CreateLruMap, LruMap}
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.CachingEvaluator.{Cache, CachedItem, GetResult, Value}

import java.util.concurrent.TimeUnit

final class CachingEvaluator[F[_], K, V](
  cache: Cache[F, K, V],
  config: CachingEvaluator.Config
) {

  def evaluateForKey(key: K, getResult: GetResult[F, V])(implicit M: Monad[F], C: Clock[F]): F[Either[Throwable, V]] =
    cache.get(key).flatMap {
      case Some(cachedItem) =>
        isExpired(cachedItem).flatMap {
          case true =>
            getResultAndCache(key, getResult, lastCachedValue = Some(cachedItem.value))
          case false =>
            Monad[F].pure(toEither(cachedItem.value))
        }
      case None =>
        getResultAndCache(key, getResult, lastCachedValue = None)
    }

  private def getResultAndCache(
    key: K,
    getResult: GetResult[F, V],
    lastCachedValue: Option[Value[V]]
  )(
    implicit M: Monad[F],
    C: Clock[F]
  ): F[Either[Throwable, V]] =
    getResult()
      .map(freshResult => toCacheValue(lastCachedValue, freshResult))
      .flatTap(freshResult => putToCache(key, freshResult))
      .map(toEither)

  private def toCacheValue(lastCachedValue: Option[Value[V]], freshResult: Either[Throwable, V]): Value[V] =
    freshResult match {
      case Right(value) =>
        Value.Success(value)
      case Left(freshError) =>
        Value.Error(freshError, extractLastKnownSuccess(lastCachedValue))
    }

  private def extractLastKnownSuccess(lastCachedValue: Option[Value[V]]): Option[V] =
    lastCachedValue match {
      case Some(Value.Success(value)) => Some(value)
      case Some(Value.Error(_, lastKnownSuccess)) => lastKnownSuccess
      case None => None
    }

  private def toEither(value: Value[V]): Either[Throwable, V] =
    value match {
      case Value.Success(value) => Right(value)
      case Value.Error(_, Some(lastSuccess)) => Right(lastSuccess)
      case Value.Error(ex, None) => Left(ex)
    }

  private def putToCache(
    key: K,
    result: Value[V]
  )(
    implicit M: Monad[F],
    C: Clock[F]
  ): F[Unit] =
    for {
      storedAt <- getCurrentTime
      _ <- cache.put(key, CachedItem(result, storedAt))
    } yield ()

  private def isExpired(cachedItem: CachedItem[V])(implicit M: Monad[F], C: Clock[F]): F[Boolean] = {
    val ttlToUse = resolveProperTtl(cachedItem.value)
    getCurrentTime.map { currentTime =>
      currentTime - cachedItem.storedAt > ttlToUse
    }
  }

  private def resolveProperTtl(value: Value[V]): Int =
    value match {
      case _: Value.Success[V] => config.successTtl
      case _: Value.Error[V] => config.errorTtl
    }

  private def getCurrentTime(implicit C: Clock[F]): F[Long] = C.realTime(TimeUnit.SECONDS)

}

object CachingEvaluator {

  type Cache[F[_], K, V] = LruMap[F, K, CachedItem[V]]
  type GetResult[F[_], V] = () => F[Either[Throwable, V]]

  sealed trait Value[+V]
  object Value {
    final case class Success[V](value: V) extends Value[V]
    final case class Error[V](value: Throwable, lastKnownSuccess: Option[V]) extends Value[V]
  }

  final case class CachedItem[V](value: Value[V], storedAt: Long)

  final case class Config(
    size: Int,
    successTtl: Int,
    errorTtl: Int
  )

  def create[F[_]: Monad, K, V](config: Config)(implicit CLM: CreateLruMap[F, K, CachedItem[V]]): F[CachingEvaluator[F, K, V]] =
    CLM
      .create(config.size)
      .map(cache => new CachingEvaluator(cache, config))
}
