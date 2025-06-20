/*
 * Copyright (c) 2012-2023 Snowplow Analytics Ltd. All rights reserved.
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

import java.net.URI
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration

import cats.data.EitherT

import cats.effect.{Async, Blocker, Clock, ContextShift, Sync}

import org.joda.money.CurrencyUnit

import com.snowplowanalytics.iglu.core.SchemaKey

import com.snowplowanalytics.forex.model.AccountType

import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.apirequest.{ApiRequestEnrichment, HttpApi}
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.sqlquery.{Rdbms, SqlQueryEnrichment}
import com.snowplowanalytics.snowplow.enrich.common.utils.{HttpClient, ShiftExecution}

sealed trait EnrichmentConf {

  /** Iglu schema key to identify the enrichment in bad row, some enrichments don't use it */
  def schemaKey: SchemaKey

  /**
   * List of files, such as local DBs that need to be downloaded and distributed across workers
   * First element of pair is URI to download file from, second is a local path to store it in
   */
  def filesToCache: List[(URI, String)] = Nil
}

object EnrichmentConf {

  final case class ApiRequestConf(
    schemaKey: SchemaKey,
    inputs: List[apirequest.Input],
    api: HttpApi,
    outputs: List[apirequest.Output],
    cache: apirequest.Cache,
    ignoreOnError: Boolean
  ) extends EnrichmentConf {
    def enrichment[F[_]: Async: Clock](httpClient: HttpClient[F]): F[ApiRequestEnrichment[F]] =
      ApiRequestEnrichment.create[F](
        schemaKey,
        inputs,
        api,
        outputs,
        cache,
        ignoreOnError,
        httpClient
      )
  }

  final case class ExtraApiRequestConf(apiRequestConf: ApiRequestConf) extends EnrichmentConf {
    def schemaKey: SchemaKey = apiRequestConf.schemaKey
    override def filesToCache: List[(URI, String)] = apiRequestConf.filesToCache
    def enrichment[F[_]: Async: Clock](httpClient: HttpClient[F]): F[ApiRequestEnrichment[F]] =
      apiRequestConf.enrichment(httpClient)
  }

  final case class PiiPseudonymizerConf(
    schemaKey: SchemaKey,
    fieldList: List[pii.PiiField],
    emitIdentificationEvent: Boolean,
    strategy: pii.PiiStrategy
  ) extends EnrichmentConf {
    def enrichment: pii.PiiPseudonymizerEnrichment =
      pii.PiiPseudonymizerEnrichment(fieldList, emitIdentificationEvent, strategy)
  }

  final case class SqlQueryConf(
    schemaKey: SchemaKey,
    inputs: List[sqlquery.Input],
    db: Rdbms,
    query: SqlQueryEnrichment.Query,
    output: sqlquery.Output,
    cache: SqlQueryEnrichment.Cache,
    ignoreOnError: Boolean
  ) extends EnrichmentConf {
    def enrichment[F[_]: Async: Clock: ContextShift](blocker: Blocker, shifter: ShiftExecution[F]): F[SqlQueryEnrichment[F]] =
      SqlQueryEnrichment.create[F](
        schemaKey,
        inputs,
        db,
        query,
        output,
        cache,
        ignoreOnError,
        blocker,
        shifter
      )
  }

  final case class AnonIpConf(
    schemaKey: SchemaKey,
    octets: AnonIPv4Octets.AnonIPv4Octets,
    segments: AnonIPv6Segments.AnonIPv6Segments
  ) extends EnrichmentConf {
    def enrichment: AnonIpEnrichment = AnonIpEnrichment(octets, segments)
  }

  final case class CampaignAttributionConf(
    schemaKey: SchemaKey,
    mediumParameters: List[String],
    sourceParameters: List[String],
    termParameters: List[String],
    contentParameters: List[String],
    campaignParameters: List[String],
    clickIdParameters: List[(String, String)]
  ) extends EnrichmentConf {
    def enrichment: CampaignAttributionEnrichment =
      CampaignAttributionEnrichment(
        mediumParameters,
        sourceParameters,
        termParameters,
        contentParameters,
        campaignParameters,
        clickIdParameters
      )
  }

  final case class CookieExtractorConf(
    schemaKey: SchemaKey,
    cookieNames: List[String]
  ) extends EnrichmentConf {
    def enrichment: CookieExtractorEnrichment = CookieExtractorEnrichment(cookieNames)
  }

  final case class CurrencyConversionConf(
    schemaKey: SchemaKey,
    accountType: AccountType,
    apiKey: String,
    baseCurrency: CurrencyUnit
  ) extends EnrichmentConf {
    def enrichment[F[_]: Async]: F[CurrencyConversionEnrichment[F]] =
      CurrencyConversionEnrichment.create[F](
        schemaKey,
        apiKey,
        accountType,
        baseCurrency
      )
  }

  final case class EventFingerprintConf(
    schemaKey: SchemaKey,
    algorithm: String => String,
    excludedParameters: List[String]
  ) extends EnrichmentConf {
    def enrichment: EventFingerprintEnrichment =
      EventFingerprintEnrichment(algorithm, excludedParameters)
  }

  final case class HttpHeaderExtractorConf(
    schemaKey: SchemaKey,
    headersPattern: String
  ) extends EnrichmentConf {
    def enrichment: HttpHeaderExtractorEnrichment = HttpHeaderExtractorEnrichment(headersPattern)
  }

  final case class IabConf(
    schemaKey: SchemaKey,
    ipFile: (URI, String),
    excludeUaFile: (URI, String),
    includeUaFile: (URI, String)
  ) extends EnrichmentConf {
    override val filesToCache: List[(URI, String)] = List(ipFile, excludeUaFile, includeUaFile)
    def enrichment[F[_]: Sync]: F[IabEnrichment] =
      IabEnrichment.create[F](schemaKey, ipFile._2, excludeUaFile._2, includeUaFile._2)
  }

  final case class IpLookupsConf(
    schemaKey: SchemaKey,
    geoFile: Option[(URI, String)],
    ispFile: Option[(URI, String)],
    domainFile: Option[(URI, String)],
    connectionTypeFile: Option[(URI, String)]
  ) extends EnrichmentConf {
    override val filesToCache: List[(URI, String)] =
      List(geoFile, ispFile, domainFile, connectionTypeFile).flatten
    def enrichment[F[_]: Async: ContextShift](blocker: Blocker): F[IpLookupsEnrichment[F]] =
      IpLookupsEnrichment.create[F](
        blocker,
        geoFile.map(_._2),
        ispFile.map(_._2),
        domainFile.map(_._2),
        connectionTypeFile.map(_._2)
      )
  }

  final case class JavascriptScriptConf(schemaKey: SchemaKey, rawFunction: String) extends EnrichmentConf {
    def enrichment: JavascriptScriptEnrichment = JavascriptScriptEnrichment(schemaKey, rawFunction)
  }

  final case class RefererParserConf(
    schemaKey: SchemaKey,
    refererDatabase: (URI, String),
    internalDomains: List[String]
  ) extends EnrichmentConf {
    override val filesToCache: List[(URI, String)] = List(refererDatabase)
    def enrichment[F[_]: Sync]: EitherT[F, String, RefererParserEnrichment] =
      RefererParserEnrichment.create[F](refererDatabase._2, internalDomains)
  }

  final case class UaParserConf(schemaKey: SchemaKey, uaDatabase: Option[(URI, String)]) extends EnrichmentConf {
    override val filesToCache: List[(URI, String)] = List(uaDatabase).flatten
    def enrichment[F[_]: Async]: EitherT[F, String, UaParserEnrichment[F]] =
      UaParserEnrichment.create[F](
        schemaKey,
        uaDatabase.map(_._2)
      )
  }

  final case class UserAgentUtilsConf(schemaKey: SchemaKey) extends EnrichmentConf {
    def enrichment: UserAgentUtilsEnrichment = UserAgentUtilsEnrichment(schemaKey)
  }

  final case class WeatherConf(
    schemaKey: SchemaKey,
    apiHost: String,
    apiKey: String,
    timeout: Int,
    cacheSize: Int,
    geoPrecision: Int
  ) extends EnrichmentConf {
    def enrichment[F[_]: Async]: EitherT[F, String, WeatherEnrichment[F]] =
      WeatherEnrichment.create[F](
        schemaKey,
        apiHost,
        apiKey,
        FiniteDuration(timeout.toLong, TimeUnit.SECONDS),
        ssl = true,
        cacheSize,
        geoPrecision
      )
  }

  final case class YauaaConf(
    schemaKey: SchemaKey,
    cacheSize: Option[Int]
  ) extends EnrichmentConf {
    def enrichment: YauaaEnrichment = YauaaEnrichment(cacheSize)
  }
}
