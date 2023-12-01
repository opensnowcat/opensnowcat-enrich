/*
 * Copyright (c) 2020-2021 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.common.fs2.io

import retry.{RetryPolicies, RetryPolicy}

import cats.Applicative

import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.BackoffPolicy

object Retries {

  def fullJitter[F[_]: Applicative](config: BackoffPolicy): RetryPolicy[F] =
    capBackoffAndRetries(config, RetryPolicies.fullJitter[F](config.minBackoff))

  def fibonacci[F[_]: Applicative](config: BackoffPolicy): RetryPolicy[F] =
    capBackoffAndRetries(config, RetryPolicies.fibonacciBackoff[F](config.minBackoff))

  private def capBackoffAndRetries[F[_]: Applicative](config: BackoffPolicy, policy: RetryPolicy[F]): RetryPolicy[F] = {
    val capped = RetryPolicies.capDelay[F](config.maxBackoff, policy)
    config.maxRetries.fold(capped)(max => capped.join(RetryPolicies.limitRetries(max)))
  }

}
