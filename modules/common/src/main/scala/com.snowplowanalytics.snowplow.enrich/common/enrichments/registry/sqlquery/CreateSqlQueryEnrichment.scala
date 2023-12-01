/*
 * Copyright (c) 2012-2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.sqlquery

import cats.Monad
import cats.effect.Clock
import cats.implicits._
import com.snowplowanalytics.iglu.core.SelfDescribingData
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.CachingEvaluator
import com.zaxxer.hikari.HikariDataSource
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.EnrichmentConf.SqlQueryConf
import com.snowplowanalytics.snowplow.enrich.common.utils.{BlockerF, ResourceF, ShiftExecution}
import io.circe.Json

import scala.collection.immutable.IntMap

/** Initialize resources, necessary for SQL Query enrichment: cache and connection */
sealed trait CreateSqlQueryEnrichment[F[_]] {
  def create(
    conf: SqlQueryConf,
    blocker: BlockerF[F],
    shifter: ShiftExecution[F]
  ): F[SqlQueryEnrichment[F]]
}

object CreateSqlQueryEnrichment {

  def apply[F[_]](implicit ev: CreateSqlQueryEnrichment[F]): CreateSqlQueryEnrichment[F] = ev

  implicit def createSqlQueryEnrichment[F[_]: DbExecutor: Monad: ResourceF: Clock](
    implicit CLM: SqlCacheInit[F]
  ): CreateSqlQueryEnrichment[F] =
    new CreateSqlQueryEnrichment[F] {
      def create(
        conf: SqlQueryConf,
        blocker: BlockerF[F],
        shifter: ShiftExecution[F]
      ): F[SqlQueryEnrichment[F]] = {
        val cacheConfig = CachingEvaluator.Config(
          size = conf.cache.size,
          successTtl = conf.cache.ttl,
          errorTtl = conf.cache.ttl / 10
        )

        CachingEvaluator
          .create[F, IntMap[Input.ExtractedValue], List[SelfDescribingData[Json]]](cacheConfig)
          .map { evaluator =>
            SqlQueryEnrichment(
              conf.schemaKey,
              conf.inputs,
              conf.db,
              conf.query,
              conf.output,
              evaluator,
              blocker,
              shifter,
              getDataSource(conf.db),
              conf.ignoreOnError
            )
          }
      }
    }

  private def getDataSource(rdbms: Rdbms): HikariDataSource = {
    val source = new HikariDataSource()
    source.setJdbcUrl(rdbms.connectionString)
    source.setMaximumPoolSize(1) // see https://github.com/snowplow/enrich/issues/549
    source
  }
}
