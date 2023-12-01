/*
 * Copyright (c) 2021 Snowplow Analytics Ltd. All rights reserved.
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

import cats.effect.{Blocker, ContextShift}

/**
 * An execution context that is safe to use for blocking operations
 *
 * BlockerF is similar to a cats.effect.Blocker, except that `blockOn` does not require an implicit
 * ContextShift; instead, the ContextShift is bound to the instance of the trait when it is
 * constructed.
 *
 * This is a bit of a hack... but it allows us to define a BlockerF[Id], which is a requirement for
 * non-fs2 apps.
 */
trait BlockerF[F[_]] {

  def blockOn[A](f: F[A]): F[A]

}

object BlockerF {

  def ofBlocker[F[_]: ContextShift](blocker: Blocker): BlockerF[F] =
    new BlockerF[F] {
      override def blockOn[A](f: F[A]): F[A] =
        blocker.blockOn(f)
    }

  def noop[F[_]]: BlockerF[F] =
    new BlockerF[F] {
      override def blockOn[A](f: F[A]): F[A] =
        f
    }

}
