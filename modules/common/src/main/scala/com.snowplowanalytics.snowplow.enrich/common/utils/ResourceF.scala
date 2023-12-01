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
package com.snowplowanalytics.snowplow.enrich.common.utils

import cats.Id

import cats.effect.{Resource, Sync}

trait ResourceF[F[_]] {
  def use[A, B](resource: A)(release: A => Unit)(use: A => F[B]): F[B]
}

object ResourceF {

  def apply[F[_]](implicit ev: ResourceF[F]): ResourceF[F] = ev

  implicit def syncResource[F[_]: Sync]: ResourceF[F] =
    new ResourceF[F] {
      def use[A, B](resource: A)(release: A => Unit)(use: A => F[B]): F[B] =
        Resource.make(Sync[F].pure(resource))(a => Sync[F].delay(release(a))).use(a => use(a))
    }

  implicit def idResource: ResourceF[Id] =
    new ResourceF[Id] {
      def use[A, B](resource: A)(release: A => Unit)(use: A => B): B =
        try use(resource)
        finally release(resource)
    }
}
