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
package com.snowplowanalytics.snowplow.enrich.common.enrichments.registry

import java.net.URI

import cats.{Functor, Monad}
import cats.data.EitherT

import org.joda.money.CurrencyUnit

import com.snowplowanalytics.iglu.core.SchemaKey

import com.snowplowanalytics.forex.CreateForex
import com.snowplowanalytics.forex.model.AccountType
import com.snowplowanalytics.maxmind.iplookups.CreateIpLookups
import com.snowplowanalytics.refererparser.CreateParser
import com.snowplowanalytics.weather.providers.openweather.CreateOWM

import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.apirequest.{
  ApiRequestEnrichment,
  CreateApiRequestEnrichment,
  HttpApi
}
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.sqlquery.{CreateSqlQueryEnrichment, Rdbms, SqlQueryEnrichment}
import com.snowplowanalytics.snowplow.enrich.common.utils.{BlockerF, ShiftExecution}

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
    def enrichment[F[_]: CreateApiRequestEnrichment]: F[ApiRequestEnrichment[F]] =
      ApiRequestEnrichment[F](this)
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
    def enrichment[F[_]: Monad: CreateSqlQueryEnrichment](blocker: BlockerF[F], shifter: ShiftExecution[F]): F[SqlQueryEnrichment[F]] =
      SqlQueryEnrichment[F](this, blocker, shifter)
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
    def enrichment[F[_]: Monad: CreateForex]: F[CurrencyConversionEnrichment[F]] =
      CurrencyConversionEnrichment[F](this)
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
    def enrichment[F[_]: Monad: CreateIabClient]: F[IabEnrichment] =
      IabEnrichment[F](this)
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
    def enrichment[F[_]: Functor: CreateIpLookups](blocker: BlockerF[F]): F[IpLookupsEnrichment[F]] =
      IpLookupsEnrichment[F](this, blocker)
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
    def enrichment[F[_]: Monad: CreateParser]: EitherT[F, String, RefererParserEnrichment] =
      RefererParserEnrichment[F](this)
  }

  final case class UaParserConf(schemaKey: SchemaKey, uaDatabase: Option[(URI, String)]) extends EnrichmentConf {
    override val filesToCache: List[(URI, String)] = List(uaDatabase).flatten
    def enrichment[F[_]: Monad: CreateUaParserEnrichment]: EitherT[F, String, UaParserEnrichment[F]] =
      UaParserEnrichment[F](this)
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
    def enrichment[F[_]: Monad: CreateOWM]: EitherT[F, String, WeatherEnrichment[F]] =
      WeatherEnrichment[F](this)
  }

  final case class YauaaConf(
    schemaKey: SchemaKey,
    cacheSize: Option[Int]
  ) extends EnrichmentConf {
    def enrichment: YauaaEnrichment = YauaaEnrichment(cacheSize)
  }
}
