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
package com.snowplowanalytics.snowplow.enrich.common.enrichments

import cats.Monad
import cats.data.{EitherT, NonEmptyList, ValidatedNel}

import cats.effect.Clock
import cats.implicits._

import io.circe._
import io.circe.syntax._

import com.snowplowanalytics.iglu.core.{SchemaCriterion, SchemaKey, SelfDescribingData}
import com.snowplowanalytics.iglu.core.circe.implicits._

import com.snowplowanalytics.iglu.client.IgluCirceClient
import com.snowplowanalytics.iglu.client.resolver.registries.RegistryLookup

import com.snowplowanalytics.forex.CreateForex
import com.snowplowanalytics.maxmind.iplookups.CreateIpLookups
import com.snowplowanalytics.refererparser.CreateParser
import com.snowplowanalytics.weather.providers.openweather.CreateOWM

import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.EnrichmentConf._

import com.snowplowanalytics.snowplow.enrich.common.utils.{BlockerF, CirceUtils, ShiftExecution}
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry._
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.apirequest.ApiRequestEnrichment
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.pii.PiiPseudonymizerEnrichment
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.sqlquery.SqlQueryEnrichment

/** Companion which holds a constructor for the EnrichmentRegistry. */
object EnrichmentRegistry {

  private val EnrichmentConfigSchemaCriterion =
    SchemaCriterion("com.snowplowanalytics.snowplow", "enrichments", "jsonschema", 1, 0)

  /**
   * Constructs our EnrichmentRegistry from the supplied JSON JValue.
   * @param json A Json representing an array of enrichment JSONs
   * @param localMode Whether to use the local MaxMind data file, enabled for tests
   * @param client The Iglu client used for schema lookup and validation
   * @return Validation boxing an EnrichmentRegistry object containing enrichments configured from
   * node
   */
  def parse[F[_]: Monad: RegistryLookup: Clock](
    json: Json,
    client: IgluCirceClient[F],
    localMode: Boolean
  ): F[ValidatedNel[String, List[EnrichmentConf]]] =
    (for {
      sd <- EitherT.fromEither[F](
              SelfDescribingData.parse(json).leftMap(parseError => NonEmptyList.one(parseError.code))
            )
      _ <- client
             .check(sd)
             .leftMap(e => NonEmptyList.one(e.asJson.noSpaces))
             .subflatMap { _ =>
               EnrichmentConfigSchemaCriterion.matches(sd.schema) match {
                 case true => ().asRight
                 case false =>
                   NonEmptyList
                     .one(
                       s"Schema criterion $EnrichmentConfigSchemaCriterion does not match schema ${sd.schema}"
                     )
                     .asLeft
               }
             }
      enrichments <- EitherT.fromEither[F](sd.data.asArray match {
                       case Some(array) => array.toList.asRight
                       case _ =>
                         NonEmptyList
                           .one("Enrichments JSON is not an array, the schema should prevent this from happening")
                           .asLeft
                     })
      configs <- enrichments
                   .map { json =>
                     for {
                       sd <- EitherT.fromEither[F](
                               SelfDescribingData.parse(json).leftMap(pe => NonEmptyList.one(pe.code))
                             )
                       _ <- client
                              .check(sd)
                              .leftMap(e =>
                                NonEmptyList.one(s"Enrichment with key '${sd.schema.toSchemaUri}` is invalid - ${e.asJson.noSpaces}")
                              )
                       conf <- EitherT.fromEither[F](
                                 buildEnrichmentConfig(sd.schema, sd.data, localMode).toEither
                               )
                     } yield conf
                   }
                   .sequence
                   .map(_.flatten)
    } yield configs).toValidated

  // todo: ValidatedNel?
  def build[
    F[_]: Monad: CreateForex: CreateIabClient: CreateIpLookups: CreateOWM: CreateParser: CreateUaParserEnrichment: sqlquery.CreateSqlQueryEnrichment: apirequest.CreateApiRequestEnrichment
  ](
    confs: List[EnrichmentConf],
    blocker: BlockerF[F],
    shifter: ShiftExecution[F]
  ): EitherT[F, String, EnrichmentRegistry[F]] =
    confs.foldLeft(EitherT.pure[F, String](EnrichmentRegistry[F]())) { (er, e) =>
      e match {
        case c: ApiRequestConf =>
          for {
            enrichment <- EitherT.right(c.enrichment[F])
            registry <- er
          } yield registry.copy(apiRequest = enrichment.some)
        case c: PiiPseudonymizerConf => er.map(_.copy(piiPseudonymizer = c.enrichment.some))
        case c: SqlQueryConf =>
          for {
            enrichment <- EitherT.right(c.enrichment[F](blocker, shifter))
            registry <- er
          } yield registry.copy(sqlQuery = enrichment.some)
        case c: AnonIpConf => er.map(_.copy(anonIp = c.enrichment.some))
        case c: CampaignAttributionConf => er.map(_.copy(campaignAttribution = c.enrichment.some))
        case c: CookieExtractorConf => er.map(_.copy(cookieExtractor = c.enrichment.some))
        case c: CurrencyConversionConf =>
          for {
            enrichment <- EitherT.right(c.enrichment[F])
            registry <- er
          } yield registry.copy(currencyConversion = enrichment.some)
        case c: EventFingerprintConf => er.map(_.copy(eventFingerprint = c.enrichment.some))
        case c: HttpHeaderExtractorConf => er.map(_.copy(httpHeaderExtractor = c.enrichment.some))
        case c: IabConf =>
          for {
            enrichment <- EitherT.right(c.enrichment[F])
            registry <- er
          } yield registry.copy(iab = enrichment.some)
        case c: IpLookupsConf =>
          for {
            enrichment <- EitherT.right(c.enrichment[F](blocker))
            registry <- er
          } yield registry.copy(ipLookups = enrichment.some)
        case c: JavascriptScriptConf => er.map(_.copy(javascriptScript = c.enrichment.some))
        case c: RefererParserConf =>
          for {
            enrichment <- c.enrichment[F]
            registry <- er
          } yield registry.copy(refererParser = enrichment.some)
        case c: UaParserConf =>
          for {
            enrichment <- c.enrichment[F]
            registry <- er
          } yield registry.copy(uaParser = enrichment.some)
        case c: UserAgentUtilsConf => er.map(_.copy(userAgentUtils = c.enrichment.some))
        case c: WeatherConf =>
          for {
            enrichment <- c.enrichment[F]
            registry <- er
          } yield registry.copy(weather = enrichment.some)
        case c: YauaaConf => er.map(_.copy(yauaa = c.enrichment.some))
      }
    }

  /**
   * Builds an EnrichmentConf from a Json if it has a recognized name field and matches a schema key
   * @param enrichmentConfig Json with enrichment information
   * @param schemaKey SchemaKey for the Json
   * @param localMode Whether to use local data files, enabled for tests
   * @return ValidatedNelMessage boxing Option boxing an enrichment configuration
   */
  private def buildEnrichmentConfig(
    schemaKey: SchemaKey,
    enrichmentConfig: Json,
    localMode: Boolean
  ): ValidatedNel[String, Option[EnrichmentConf]] =
    CirceUtils.extract[Boolean](enrichmentConfig, "enabled").toEither match {
      case Right(false) => None.validNel // Enrichment is disabled
      case _ =>
        schemaKey.name match {
          case "ip_lookups" =>
            IpLookupsEnrichment.parse(enrichmentConfig, schemaKey, localMode).map(_.some)
          case "anon_ip" =>
            AnonIpEnrichment.parse(enrichmentConfig, schemaKey).map(_.some)
          case "referer_parser" =>
            RefererParserEnrichment.parse(enrichmentConfig, schemaKey, localMode).map(_.some)
          case "campaign_attribution" =>
            CampaignAttributionEnrichment.parse(enrichmentConfig, schemaKey).map(_.some)
          case "user_agent_utils_config" =>
            UserAgentUtilsEnrichmentConfig.parse(enrichmentConfig, schemaKey).map(_.some)
          case "ua_parser_config" =>
            UaParserEnrichment.parse(enrichmentConfig, schemaKey).map(_.some)
          case "yauaa_enrichment_config" =>
            YauaaEnrichment.parse(enrichmentConfig, schemaKey).map(_.some)
          case "currency_conversion_config" =>
            CurrencyConversionEnrichment
              .parse(enrichmentConfig, schemaKey)
              .map(_.some)
          case "javascript_script_config" =>
            JavascriptScriptEnrichment
              .parse(enrichmentConfig, schemaKey)
              .map(_.some)
          case "event_fingerprint_config" =>
            EventFingerprintEnrichment
              .parse(enrichmentConfig, schemaKey)
              .map(_.some)
          case "cookie_extractor_config" =>
            CookieExtractorEnrichment
              .parse(enrichmentConfig, schemaKey)
              .map(_.some)
          case "http_header_extractor_config" =>
            HttpHeaderExtractorEnrichment
              .parse(enrichmentConfig, schemaKey)
              .map(_.some)
          case "weather_enrichment_config" =>
            WeatherEnrichment.parse(enrichmentConfig, schemaKey).map(_.some)
          case "api_request_enrichment_config" =>
            ApiRequestEnrichment.parse(enrichmentConfig, schemaKey).map(_.some)
          case "sql_query_enrichment_config" =>
            SqlQueryEnrichment.parse(enrichmentConfig, schemaKey).map(_.some)
          case "pii_enrichment_config" =>
            PiiPseudonymizerEnrichment.parse(enrichmentConfig, schemaKey).map(_.some)
          case "iab_spiders_and_robots_enrichment" =>
            IabEnrichment.parse(enrichmentConfig, schemaKey, localMode).map(_.some)
          case _ =>
            Option.empty[EnrichmentConf].validNel // Enrichment is not recognized
        }
    }
}

/** A registry to hold all of our enrichments. */
final case class EnrichmentRegistry[F[_]](
  apiRequest: Option[ApiRequestEnrichment[F]] = None,
  piiPseudonymizer: Option[PiiPseudonymizerEnrichment] = None,
  sqlQuery: Option[SqlQueryEnrichment[F]] = None,
  anonIp: Option[AnonIpEnrichment] = None,
  campaignAttribution: Option[CampaignAttributionEnrichment] = None,
  cookieExtractor: Option[CookieExtractorEnrichment] = None,
  currencyConversion: Option[CurrencyConversionEnrichment[F]] = None,
  eventFingerprint: Option[EventFingerprintEnrichment] = None,
  httpHeaderExtractor: Option[HttpHeaderExtractorEnrichment] = None,
  iab: Option[IabEnrichment] = None,
  ipLookups: Option[IpLookupsEnrichment[F]] = None,
  javascriptScript: Option[JavascriptScriptEnrichment] = None,
  refererParser: Option[RefererParserEnrichment] = None,
  uaParser: Option[UaParserEnrichment[F]] = None,
  userAgentUtils: Option[UserAgentUtilsEnrichment] = None,
  weather: Option[WeatherEnrichment[F]] = None,
  yauaa: Option[YauaaEnrichment] = None
)
