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
package com.snowplowanalytics.snowplow.enrich.common
package enrichments

import java.nio.charset.Charset
import java.net.URI
import java.time.Instant
import org.joda.time.DateTime
import io.circe.Json
import cats.{Applicative, Monad}
import cats.data.{EitherT, NonEmptyList, OptionT, StateT}
import cats.effect.Clock
import cats.implicits._

import com.snowplowanalytics.refererparser._

import com.snowplowanalytics.iglu.client.IgluCirceClient
import com.snowplowanalytics.iglu.client.resolver.registries.RegistryLookup

import com.snowplowanalytics.iglu.core.SelfDescribingData
import com.snowplowanalytics.iglu.core.circe.implicits._

import com.snowplowanalytics.snowplow.badrows._
import com.snowplowanalytics.snowplow.badrows.{FailureDetails, Payload, Processor}

import adapters.RawEvent
import enrichments.{EventEnrichments => EE}
import enrichments.{MiscEnrichments => ME}
import enrichments.registry._
import enrichments.registry.apirequest.ApiRequestEnrichment
import enrichments.registry.pii.PiiPseudonymizerEnrichment
import enrichments.registry.sqlquery.SqlQueryEnrichment
import enrichments.web.{PageEnrichments => WPE}
import outputs.EnrichedEvent
import utils.{IgluUtils, ConversionUtils => CU}

object EnrichmentManager {

  /**
   * Run the enrichment workflow
   * @param registry Contain configuration for all enrichments to apply
   * @param client Iglu Client, for schema lookups and validation
   * @param processor Meta information about processing asset, for bad rows
   * @param etlTstamp ETL timestamp
   * @param raw Canonical input event to enrich
   * @param featureFlags The feature flags available in the current version of Enrich
   * @param invalidCount Function to increment the count of invalid events
   * @return Enriched event or bad row if a problem occured
   */
  def enrichEvent[F[_]: Monad: RegistryLookup: Clock](
    registry: EnrichmentRegistry[F],
    client: IgluCirceClient[F],
    processor: Processor,
    etlTstamp: DateTime,
    raw: RawEvent,
    featureFlags: EtlPipeline.FeatureFlags,
    invalidCount: F[Unit]
  ): EitherT[F, BadRow, EnrichedEvent] =
    for {
      enriched <- EitherT.fromEither[F](setupEnrichedEvent(raw, etlTstamp, processor))
      extractResult <- IgluUtils.extractAndValidateInputJsons(enriched, client, raw, processor)
      _ = {
        ME.formatUnstructEvent(extractResult.unstructEvent).foreach(e => enriched.unstruct_event = e)
        ME.formatContexts(extractResult.contexts).foreach(c => enriched.contexts = c)
      }
      enrichmentsContexts <- runEnrichments(
                               registry,
                               processor,
                               raw,
                               enriched,
                               extractResult.contexts,
                               extractResult.unstructEvent,
                               featureFlags.legacyEnrichmentOrder
                             )
      _ = ME.formatContexts(enrichmentsContexts ::: extractResult.validationInfoContexts).foreach(c => enriched.derived_contexts = c)
      _ <- IgluUtils
             .validateEnrichmentsContexts[F](client, enrichmentsContexts, raw, processor, enriched)
      _ <- EitherT.rightT[F, BadRow](
             anonIp(enriched, registry.anonIp).foreach(enriched.user_ipaddress = _)
           )
      _ <- EitherT.rightT[F, BadRow] {
             piiTransform(enriched, registry.piiPseudonymizer).foreach { pii =>
               enriched.pii = pii.asString
             }
           }
      _ <- validateEnriched(enriched, raw, processor, featureFlags.acceptInvalid, invalidCount)
    } yield enriched

  /**
   * Run all the enrichments and aggregate the errors if any
   * @param enriched /!\ MUTABLE enriched event, mutated IN-PLACE /!\
   * @return List of contexts to attach to the enriched event if all the enrichments went well
   *         or [[BadRow.EnrichmentFailures]] if something wrong happened
   *         with at least one enrichment
   */
  private def runEnrichments[F[_]: Monad](
    registry: EnrichmentRegistry[F],
    processor: Processor,
    raw: RawEvent,
    enriched: EnrichedEvent,
    inputContexts: List[SelfDescribingData[Json]],
    unstructEvent: Option[SelfDescribingData[Json]],
    legacyOrder: Boolean
  ): EitherT[F, BadRow.EnrichmentFailures, List[SelfDescribingData[Json]]] =
    EitherT {
      accState(registry, raw, inputContexts, unstructEvent, legacyOrder)
        .runS(Accumulation(enriched, Nil, Nil))
        .map {
          case Accumulation(_, failures, contexts) =>
            failures.toNel match {
              case Some(nel) =>
                buildEnrichmentFailuresBadRow(
                  nel,
                  EnrichedEvent.toPartiallyEnrichedEvent(enriched),
                  RawEvent.toRawEvent(raw),
                  processor
                ).asLeft
              case None =>
                contexts.asRight
            }
        }
    }

  private[enrichments] case class Accumulation(
    event: EnrichedEvent,
    errors: List[FailureDetails.EnrichmentFailure],
    contexts: List[SelfDescribingData[Json]]
  )
  private[enrichments] type EStateT[F[_], A] = StateT[F, Accumulation, A]

  private object EStateT {
    def apply[F[_]: Applicative, A](f: Accumulation => F[(Accumulation, A)]): EStateT[F, A] =
      StateT(f)

    def fromEither[F[_]: Applicative](
      f: (EnrichedEvent,
        List[SelfDescribingData[Json]]) => Either[NonEmptyList[FailureDetails.EnrichmentFailure], List[SelfDescribingData[Json]]]
    ): EStateT[F, Unit] =
      fromEitherF[F] { case (x, y) => f(x, y).pure[F] }

    def fromEitherF[F[_]: Applicative](
      f: (EnrichedEvent,
        List[SelfDescribingData[Json]]) => F[Either[NonEmptyList[FailureDetails.EnrichmentFailure], List[SelfDescribingData[Json]]]]
    ): EStateT[F, Unit] =
      EStateT {
        case Accumulation(event, errors, contexts) =>
          f(event, contexts).map {
            case Right(contexts2) => (Accumulation(event, errors, contexts2 ::: contexts), ())
            case Left(moreErrors) => (Accumulation(event, moreErrors.toList ::: errors, contexts), ())
          }
      }

    def fromEitherOpt[F[_]: Applicative, A](
      f: EnrichedEvent => Either[NonEmptyList[FailureDetails.EnrichmentFailure], Option[A]]
    ): EStateT[F, Option[A]] =
      EStateT {
        case acc @ Accumulation(event, errors, contexts) =>
          f(event) match {
            case Right(opt) => (acc, opt).pure[F]
            case Left(moreErrors) => (Accumulation(event, moreErrors.toList ::: errors, contexts), Option.empty[A]).pure[F]
          }
      }
  }

  private def accState[F[_]: Monad](
    registry: EnrichmentRegistry[F],
    raw: RawEvent,
    inputContexts: List[SelfDescribingData[Json]],
    unstructEvent: Option[SelfDescribingData[Json]],
    legacyOrder: Boolean
  ): EStateT[F, Unit] = {
    val getCookieContexts = headerContexts[F, CookieExtractorEnrichment](
      raw.context.headers,
      registry.cookieExtractor,
      (e, hs) => e.extract(hs)
    )
    val getHttpHeaderContexts = headerContexts[F, HttpHeaderExtractorEnrichment](
      raw.context.headers,
      registry.httpHeaderExtractor,
      (e, hs) => e.extract(hs)
    )
    val sqlContexts = getSqlQueryContexts[F](inputContexts, unstructEvent, registry.sqlQuery)
    val apiContexts = getApiRequestContexts[F](inputContexts, unstructEvent, registry.apiRequest)

    if (legacyOrder)
      for {
        // format: off
        _       <- getCollectorVersionSet[F]                                          // The load fails if the collector version is not set
        pageUri <- getPageUri[F](raw.context.refererUri)                              // Potentially update the page_url and set the page URL components
        _       <- getDerivedTstamp[F]                                                // Calculate the derived timestamp
        _       <- getIabContext[F](registry.iab)                                     // Fetch IAB enrichment context (before anonymizing the IP address)
        _       <- getUaUtils[F](registry.userAgentUtils)                             // Parse the useragent using user-agent-utils
        _       <- getUaParser[F](registry.uaParser)                                  // Create the ua_parser_context
        _       <- getRefererUri[F](registry.refererParser)                           // Potentially set the referrer details and URL components
        qsMap   <- extractQueryString[F](pageUri, raw.source.encoding)                // Parse the page URI's querystring
        _       <- setCampaign[F](qsMap, registry.campaignAttribution)                // Marketing attribution
        _       <- getCrossDomain[F](qsMap)                                           // Cross-domain tracking
        _       <- setEventFingerprint[F](raw.parameters, registry.eventFingerprint)  // This enrichment cannot fail
        _       <- getCookieContexts                                                  // Execute cookie extractor enrichment
        _       <- getHttpHeaderContexts                                              // Execute header extractor enrichment
        _       <- getYauaaContext[F](registry.yauaa, raw.context.headers)            // Runs YAUAA enrichment (gets info thanks to user agent)
        _       <- extractSchemaFields[F](unstructEvent)                              // Extract the event vendor/name/format/version
        _       <- getJsScript[F](registry.javascriptScript)                          // Execute the JavaScript scripting enrichment
        _       <- getCurrency[F](raw.context.timestamp, registry.currencyConversion) // Finalize the currency conversion
        _       <- getWeatherContext[F](registry.weather)                             // Fetch weather context
        _       <- geoLocation[F](registry.ipLookups)                                 // Execute IP lookup enrichment
        _       <- sqlContexts                                                        // Derive some contexts with custom SQL Query enrichment
        _       <- apiContexts                                                        // Derive some contexts with custom API Request enrichment
        // format: on
      } yield ()
    else
      for {
        // format: off
        _       <- getCollectorVersionSet[F]                                          // The load fails if the collector version is not set
        pageUri <- getPageUri[F](raw.context.refererUri)                              // Potentially update the page_url and set the page URL components
        _       <- getDerivedTstamp[F]                                                // Calculate the derived timestamp
        _       <- getIabContext[F](registry.iab)                                     // Fetch IAB enrichment context (before anonymizing the IP address)
        _       <- getUaUtils[F](registry.userAgentUtils)                             // Parse the useragent using user-agent-utils
        _       <- getUaParser[F](registry.uaParser)                                  // Create the ua_parser_context
        _       <- getCurrency[F](raw.context.timestamp, registry.currencyConversion) // Finalize the currency conversion
        _       <- getRefererUri[F](registry.refererParser)                           // Potentially set the referrer details and URL components
        qsMap   <- extractQueryString[F](pageUri, raw.source.encoding)                // Parse the page URI's querystring
        _       <- setCampaign[F](qsMap, registry.campaignAttribution)                // Marketing attribution
        _       <- getCrossDomain[F](qsMap)                                           // Cross-domain tracking
        _       <- setEventFingerprint[F](raw.parameters, registry.eventFingerprint)  // This enrichment cannot fail
        _       <- getCookieContexts                                                  // Execute cookie extractor enrichment
        _       <- getHttpHeaderContexts                                              // Execute header extractor enrichment
        _       <- getWeatherContext[F](registry.weather)                             // Fetch weather context
        _       <- getYauaaContext[F](registry.yauaa, raw.context.headers)            // Runs YAUAA enrichment (gets info thanks to user agent)
        _       <- extractSchemaFields[F](unstructEvent)                              // Extract the event vendor/name/format/version
        _       <- geoLocation[F](registry.ipLookups)                                 // Execute IP lookup enrichment
        _       <- getJsScript[F](registry.javascriptScript)                          // Execute the JavaScript scripting enrichment
        _       <- sqlContexts                                                        // Derive some contexts with custom SQL Query enrichment
        _       <- apiContexts                                                        // Derive some contexts with custom API Request enrichment
        // format: on
      } yield ()

  }

  /** Create the mutable [[EnrichedEvent]] and initialize it. */
  private def setupEnrichedEvent(
    raw: RawEvent,
    etlTstamp: DateTime,
    processor: Processor
  ): Either[BadRow.EnrichmentFailures, EnrichedEvent] = {
    val e = new EnrichedEvent()
    e.event_id = EE.generateEventId() // May be updated later if we have an `eid` parameter
    e.v_collector = raw.source.name // May be updated later if we have a `cv` parameter
    e.v_etl = ME.etlVersion(processor)
    e.etl_tstamp = EE.toTimestamp(etlTstamp)
    e.network_userid = raw.context.userId.map(_.toString).orNull // May be updated later by 'nuid'
    e.user_ipaddress = ME
      .extractIp("user_ipaddress", raw.context.ipAddress.orNull)
      .toOption
      .orNull // May be updated later by 'ip'
    // May be updated later if we have a `ua` parameter
    setUseragent(e, raw.context.useragent)
    // Validate that the collectorTstamp exists and is Redshift-compatible
    val collectorTstamp = setCollectorTstamp(e, raw.context.timestamp).toValidatedNel
    // Map/validate/transform input fields to enriched event fields
    val transformed = Transform.transform(raw, e)

    (collectorTstamp |+| transformed)
      .leftMap { enrichmentFailures =>
        EnrichmentManager.buildEnrichmentFailuresBadRow(
          enrichmentFailures,
          EnrichedEvent.toPartiallyEnrichedEvent(e),
          RawEvent.toRawEvent(raw),
          processor
        )
      }
      .as(e)
      .toEither
  }

  def setCollectorTstamp(event: EnrichedEvent, timestamp: Option[DateTime]): Either[FailureDetails.EnrichmentFailure, Unit] =
    EE.formatCollectorTstamp(timestamp).map { t =>
      event.collector_tstamp = t
      ().asRight
    }

  def setUseragent(
    event: EnrichedEvent,
    useragent: Option[String]
  ): Unit =
    useragent match {
      case Some(ua) =>
        val s = ua.replaceAll("(\\r|\\n)", "").replaceAll("\\t", "    ")
        event.useragent = s
      case None => () // No fields updated
    }

  // The load fails if the collector version is not set
  def getCollectorVersionSet[F[_]: Applicative]: EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        event.v_collector match {
          case "" | null =>
            NonEmptyList
              .one(
                FailureDetails
                  .EnrichmentFailure(
                    None,
                    FailureDetails.EnrichmentFailureMessage
                      .InputData("v_collector", None, "should be set")
                  )
              )
              .asLeft
          case _ => Nil.asRight
        }
    }

  // If our IpToGeo enrichment is enabled, get the geo-location from the IP address
  // enrichment doesn't fail to maintain the previous approach where failures were suppressed
  // c.f. https://github.com/snowplow/snowplow/issues/351
  def geoLocation[F[_]: Monad](ipLookups: Option[IpLookupsEnrichment[F]]): EStateT[F, Unit] =
    EStateT.fromEitherF {
      case (event, _) =>
        val ipLookup = for {
          enrichment <- OptionT.fromOption[F](ipLookups)
          ip <- OptionT.fromOption[F](Option(event.user_ipaddress))
          result <- OptionT.liftF(enrichment.extractIpInformation(ip))
        } yield result

        ipLookup.value.map {
          case Some(lookup) =>
            lookup.ipLocation.flatMap(_.toOption).foreach { loc =>
              event.geo_country = loc.countryCode
              event.geo_region = loc.region.orNull
              event.geo_city = loc.city.orNull
              event.geo_zipcode = loc.postalCode.orNull
              event.geo_latitude = loc.latitude
              event.geo_longitude = loc.longitude
              event.geo_region_name = loc.regionName.orNull
              event.geo_timezone = loc.timezone.orNull
            }
            lookup.isp.flatMap(_.toOption).foreach { i =>
              event.ip_isp = i
            }
            lookup.organization.flatMap(_.toOption).foreach { org =>
              event.ip_organization = org
            }
            lookup.domain.flatMap(_.toOption).foreach { d =>
              event.ip_domain = d
            }
            lookup.connectionType.flatMap(_.toOption).foreach { ct =>
              event.ip_netspeed = ct
            }
            Nil.asRight
          case None =>
            Nil.asRight
        }
    }

  // Potentially update the page_url and set the page URL components
  def getPageUri[F[_]: Applicative](refererUri: Option[String]): EStateT[F, Option[URI]] =
    EStateT.fromEitherOpt { event =>
      val pageUri = WPE.extractPageUri(refererUri, Option(event.page_url))
      for {
        uri <- pageUri
        u <- uri
      } {
        // Update the page_url
        event.page_url = u.toString

        // Set the URL components
        val components = CU.explodeUri(u)
        event.page_urlscheme = components.scheme
        event.page_urlhost = components.host
        event.page_urlport = components.port
        event.page_urlpath = components.path.orNull
        event.page_urlquery = components.query.orNull
        event.page_urlfragment = components.fragment.orNull
      }
      pageUri.leftMap(NonEmptyList.one(_))
    }

  // Calculate the derived timestamp
  def getDerivedTstamp[F[_]: Applicative]: EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        EE.getDerivedTimestamp(
          Option(event.dvce_sent_tstamp),
          Option(event.dvce_created_tstamp),
          Option(event.collector_tstamp),
          Option(event.true_tstamp)
        ).map { dt =>
          dt.foreach(event.derived_tstamp = _)
          Nil
        }.leftMap(NonEmptyList.one(_))
    }

  // Fetch IAB enrichment context (before anonymizing the IP address).
  // IAB enrichment is called only if the IP is v4 (and after removing the port if any)
  // and if the user agent is defined.
  def getIabContext[F[_]: Applicative](iabEnrichment: Option[IabEnrichment]): EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        val result = for {
          iab <- iabEnrichment
          useragent <- Option(event.useragent).filter(_.trim.nonEmpty)
          ipString <- Option(event.user_ipaddress)
          ip <- CU.extractInetAddress(ipString)
          tstamp <- Option(event.derived_tstamp).map(EventEnrichments.fromTimestamp)
        } yield iab.getIabContext(useragent, ip, tstamp)

        result.sequence.bimap(NonEmptyList.one(_), _.toList)
    }

  def anonIp(event: EnrichedEvent, anonIp: Option[AnonIpEnrichment]): Option[String] =
    Option(event.user_ipaddress).map { ip =>
      anonIp match {
        case Some(anon) => anon.anonymizeIp(ip)
        case None => ip
      }
    }

  def getUaUtils[F[_]: Applicative](userAgentUtils: Option[UserAgentUtilsEnrichment]): EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        userAgentUtils match {
          case Some(uap) =>
            Option(event.useragent) match {
              case Some(ua) =>
                val ca = uap.extractClientAttributes(ua)
                ca.map { c =>
                  event.br_name = c.browserName
                  event.br_family = c.browserFamily
                  c.browserVersion.foreach(bv => event.br_version = bv)
                  event.br_type = c.browserType
                  event.br_renderengine = c.browserRenderEngine
                  event.os_name = c.osName
                  event.os_family = c.osFamily
                  event.os_manufacturer = c.osManufacturer
                  event.dvce_type = c.deviceType
                  event.dvce_ismobile = CU.booleanToJByte(c.deviceIsMobile)
                  Nil
                }.leftMap(NonEmptyList.one(_))
              case None => Nil.asRight // No fields updated
            }
          case None => Nil.asRight
        }
    }

  def getUaParser[F[_]: Monad](uaParser: Option[UaParserEnrichment[F]]): EStateT[F, Unit] =
    EStateT.fromEitherF {
      case (event, _) =>
        uaParser match {
          case Some(uap) =>
            Option(event.useragent) match {
              case Some(ua) =>
                uap.extractUserAgent(ua).map {
                  case Left(failure) => Left(NonEmptyList.one(failure))
                  case Right(context) => Right(List(context))
                }
              case None => Monad[F].pure(Nil.asRight) // No fields updated
            }
          case None => Monad[F].pure(Nil.asRight)
        }
    }

  def getCurrency[F[_]: Monad](
    timestamp: Option[DateTime],
    currencyConversion: Option[CurrencyConversionEnrichment[F]]
  ): EStateT[F, Unit] =
    EStateT.fromEitherF {
      case (event, _) =>
        currencyConversion match {
          case Some(currency) =>
            event.base_currency = currency.baseCurrency.getCode
            // Note that jBigDecimalToDouble is applied to either-valid-or-null event POJO
            // properties, so we don't expect any of these four vals to be a Failure
            val trTax = CU.jBigDecimalToDouble("tr_tx", event.tr_tax).toValidatedNel
            val tiPrice = CU.jBigDecimalToDouble("ti_pr", event.ti_price).toValidatedNel
            val trTotal = CU.jBigDecimalToDouble("tr_tt", event.tr_total).toValidatedNel
            val trShipping = CU.jBigDecimalToDouble("tr_sh", event.tr_shipping).toValidatedNel
            EitherT(
              (trTotal, trTax, trShipping, tiPrice)
                .mapN {
                  currency.convertCurrencies(
                    Option(event.tr_currency),
                    _,
                    _,
                    _,
                    Option(event.ti_currency),
                    _,
                    timestamp
                  )
                }
                .toEither
                .sequence
                .map(_.flatMap(_.toEither))
            ).map {
              case (trTotalBase, trTaxBase, trShippingBase, tiPriceBase) =>
                trTotalBase.foreach(v => event.tr_total_base = v)
                trTaxBase.foreach(v => event.tr_tax_base = v)
                trShippingBase.foreach(v => event.tr_shipping_base = v)
                tiPriceBase.foreach(v => event.ti_price_base = v)
                List.empty[SelfDescribingData[Json]]
            }.value
          case None => Monad[F].pure(Nil.asRight)
        }
    }

  def getRefererUri[F[_]: Applicative](
    refererParser: Option[RefererParserEnrichment]
  ): EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        CU.stringToUri(event.page_referrer)
          .map { uri =>
            for {
              u <- uri
            } {
              // Set the URL components
              val components = CU.explodeUri(u)
              event.refr_urlscheme = components.scheme
              event.refr_urlhost = components.host
              event.refr_urlport = components.port
              event.refr_urlpath = components.path.orNull
              event.refr_urlquery = components.query.orNull
              event.refr_urlfragment = components.fragment.orNull

              // Set the referrer details
              refererParser match {
                case Some(rp) =>
                  for (refr <- rp.extractRefererDetails(u, event.page_urlhost))
                    refr match {
                      case UnknownReferer(medium) => event.refr_medium = CU.makeTsvSafe(medium.value)
                      case SearchReferer(medium, source, term) =>
                        event.refr_medium = CU.makeTsvSafe(medium.value)
                        event.refr_source = CU.makeTsvSafe(source)
                        event.refr_term = CU.makeTsvSafe(term.orNull)
                      case InternalReferer(medium) => event.refr_medium = CU.makeTsvSafe(medium.value)
                      case SocialReferer(medium, source) =>
                        event.refr_medium = CU.makeTsvSafe(medium.value)
                        event.refr_source = CU.makeTsvSafe(source)
                      case EmailReferer(medium, source) =>
                        event.refr_medium = CU.makeTsvSafe(medium.value)
                        event.refr_source = CU.makeTsvSafe(source)
                      case PaidReferer(medium, source) =>
                        event.refr_medium = CU.makeTsvSafe(medium.value)
                        event.refr_source = CU.makeTsvSafe(source)
                    }
                case None => ()
              }
            }
            Nil
          }
          .leftMap(f =>
            NonEmptyList.one(
              FailureDetails.EnrichmentFailure(
                None,
                FailureDetails.EnrichmentFailureMessage.Simple(f)
              )
            )
          )
    }

  // Parse the page URI's querystring
  def extractQueryString[F[_]: Applicative](
    pageUri: Option[URI],
    encoding: String
  ): EStateT[F, Option[QueryStringParameters]] =
    EStateT.fromEitherOpt { _ =>
      pageUri match {
        case Some(u) =>
          CU.extractQuerystring(u, Charset.forName(encoding)).bimap(NonEmptyList.one(_), _.some)
        case None => None.asRight
      }
    }

  def setCampaign[F[_]: Applicative](
    pageQsMap: Option[QueryStringParameters],
    campaignAttribution: Option[CampaignAttributionEnrichment]
  ): EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        pageQsMap match {
          case Some(qsList) =>
            campaignAttribution match {
              case Some(ce) =>
                val cmp = ce.extractMarketingFields(qsList)
                event.mkt_medium = CU.makeTsvSafe(cmp.medium.orNull)
                event.mkt_source = CU.makeTsvSafe(cmp.source.orNull)
                event.mkt_term = CU.makeTsvSafe(cmp.term.orNull)
                event.mkt_content = CU.makeTsvSafe(cmp.content.orNull)
                event.mkt_campaign = CU.makeTsvSafe(cmp.campaign.orNull)
                event.mkt_clickid = CU.makeTsvSafe(cmp.clickId.orNull)
                event.mkt_network = CU.makeTsvSafe(cmp.network.orNull)
                Nil.asRight
              case None => Nil.asRight
            }
          case None => Nil.asRight
        }
    }

  def getCrossDomain[F[_]: Applicative](
    pageQsMap: Option[QueryStringParameters]
  ): EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        pageQsMap match {
          case Some(qsMap) =>
            val crossDomainParseResult = WPE.parseCrossDomain(qsMap)
            for ((maybeRefrDomainUserid, maybeRefrDvceTstamp) <- crossDomainParseResult.toOption) {
              maybeRefrDomainUserid.foreach(event.refr_domain_userid = _)
              maybeRefrDvceTstamp.foreach(event.refr_dvce_tstamp = _)
            }
            crossDomainParseResult.bimap(NonEmptyList.one(_), _ => Nil)
          case None => Nil.asRight
        }
    }

  def setEventFingerprint[F[_]: Applicative](
    parameters: RawEventParameters,
    eventFingerprint: Option[EventFingerprintEnrichment]
  ): EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        eventFingerprint match {
          case Some(efe) =>
            event.event_fingerprint = efe.getEventFingerprint(parameters)
            Nil.asRight
          case _ => Nil.asRight
        }
    }

  // Extract the event vendor/name/format/version
  def extractSchemaFields[F[_]: Applicative](
    unstructEvent: Option[SelfDescribingData[Json]]
  ): EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        SchemaEnrichment
          .extractSchema(event, unstructEvent)
          .map {
            case Some(schemaKey) =>
              event.event_vendor = schemaKey.vendor
              event.event_name = schemaKey.name
              event.event_format = schemaKey.format
              event.event_version = schemaKey.version.asString
              Nil
            case None => Nil
          }
          .leftMap(NonEmptyList.one(_))
    }

  // Execute the JavaScript scripting enrichment
  def getJsScript[F[_]: Applicative](
    javascriptScript: Option[JavascriptScriptEnrichment]
  ): EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, derivedContexts) =>
        javascriptScript match {
          case Some(jse) =>
            ME.formatContexts(derivedContexts).foreach(c => event.derived_contexts = c)
            jse.process(event).leftMap(NonEmptyList.one)
          case None => Nil.asRight
        }
    }

  def headerContexts[F[_]: Applicative, A](
    headers: List[String],
    enrichment: Option[A],
    f: (A, List[String]) => List[SelfDescribingData[Json]]
  ): EStateT[F, Unit] =
    EStateT.fromEither[F] {
      case (_, _) =>
        enrichment match {
          case Some(e) => f(e, headers).asRight
          case None => Nil.asRight
        }
    }

  // Fetch weather context
  def getWeatherContext[F[_]: Monad](weather: Option[WeatherEnrichment[F]]): EStateT[F, Unit] =
    EStateT.fromEitherF {
      case (event, _) =>
        weather match {
          case Some(we) =>
            we.getWeatherContext(
              Option(event.geo_latitude),
              Option(event.geo_longitude),
              Option(event.derived_tstamp).map(EventEnrichments.fromTimestamp)
            ).map(_.map(List(_)))
          case None => Monad[F].pure(Nil.asRight)
        }
    }

  def getYauaaContext[F[_]: Applicative](yauaa: Option[YauaaEnrichment], headers: List[String]): EStateT[F, Unit] =
    EStateT.fromEither {
      case (event, _) =>
        yauaa.map(_.getYauaaContext(event.useragent, headers)).toList.asRight
    }

  // Derive some contexts with custom SQL Query enrichment
  def getSqlQueryContexts[F[_]: Monad](
    inputContexts: List[SelfDescribingData[Json]],
    unstructEvent: Option[SelfDescribingData[Json]],
    sqlQuery: Option[SqlQueryEnrichment[F]]
  ): EStateT[F, Unit] =
    EStateT.fromEitherF {
      case (event, derivedContexts) =>
        sqlQuery match {
          case Some(enrichment) =>
            enrichment.lookup(event, derivedContexts, inputContexts, unstructEvent).map(_.toEither)
          case None =>
            List.empty[SelfDescribingData[Json]].asRight.pure[F]
        }
    }

  // Derive some contexts with custom API Request enrichment
  def getApiRequestContexts[F[_]: Monad](
    inputContexts: List[SelfDescribingData[Json]],
    unstructEvent: Option[SelfDescribingData[Json]],
    apiRequest: Option[ApiRequestEnrichment[F]]
  ): EStateT[F, Unit] =
    EStateT.fromEitherF {
      case (event, derivedContexts) =>
        apiRequest match {
          case Some(enrichment) =>
            enrichment.lookup(event, derivedContexts, inputContexts, unstructEvent).map(_.toEither)
          case None =>
            List.empty[SelfDescribingData[Json]].asRight.pure[F]
        }
    }

  def piiTransform(event: EnrichedEvent, piiPseudonymizer: Option[PiiPseudonymizerEnrichment]): Option[SelfDescribingData[Json]] =
    piiPseudonymizer.flatMap(_.transformer(event))

  /** Build `BadRow.EnrichmentFailures` from a list of `FailureDetails.EnrichmentFailure`s */
  def buildEnrichmentFailuresBadRow(
    fs: NonEmptyList[FailureDetails.EnrichmentFailure],
    pee: Payload.PartiallyEnrichedEvent,
    re: Payload.RawEvent,
    processor: Processor
  ) =
    BadRow.EnrichmentFailures(
      processor,
      Failure.EnrichmentFailures(Instant.now(), fs),
      Payload.EnrichmentPayload(pee, re)
    )

  /**
   * Validates enriched events against atomic schema.
   * For now it's possible to accept enriched events that are not valid.
   * See https://github.com/snowplow/enrich/issues/517#issuecomment-1033910690
   */
  private def validateEnriched[F[_]: Clock: Monad: RegistryLookup](
    enriched: EnrichedEvent,
    raw: RawEvent,
    processor: Processor,
    acceptInvalid: Boolean,
    invalidCount: F[Unit]
  ): EitherT[F, BadRow, Unit] =
    EitherT {

      //We're using static field's length validation. See more in https://github.com/snowplow/enrich/issues/608
      AtomicFieldsLengthValidator.validate[F](enriched, raw, processor, acceptInvalid, invalidCount)
    }
}
