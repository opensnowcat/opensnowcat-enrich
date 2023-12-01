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
package com.snowplowanalytics.snowplow.enrich.common.fs2.test

import cats.effect.Sync
import cats.effect.concurrent.Ref
import fs2.Stream
import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import com.snowplowanalytics.snowplow.enrich.common.fs2.io.experimental.Metadata
import com.snowplowanalytics.snowplow.enrich.common.fs2.io.experimental.Metadata.{EntitiesAndCount, MetadataEvent}

object AggregatesSpec {
  def init[F[_]: Sync] = Ref.of[F, Map[MetadataEvent, EntitiesAndCount]](Map.empty)

  def metadata[F[_]](ref: Ref[F, Map[MetadataEvent, EntitiesAndCount]]): Metadata[F] =
    new Metadata[F] {
      def report: Stream[F, Unit] = Stream.empty.covary[F]
      def observe(events: List[EnrichedEvent]): F[Unit] =
        ref.update(Metadata.recalculate(_, events))
    }
}
