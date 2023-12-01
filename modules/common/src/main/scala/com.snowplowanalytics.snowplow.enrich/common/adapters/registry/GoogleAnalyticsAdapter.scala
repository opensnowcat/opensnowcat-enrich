/*
 * Copyright (c) 2018-2022 Snowplow Analytics Ltd. All rights reserved.
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
package adapters
package registry

import scala.annotation.tailrec

import cats.{Applicative, Functor, Monad}
import cats.data.{NonEmptyList, ValidatedNel}
import cats.implicits._

import cats.effect.Clock

import io.circe._
import io.circe.syntax._

import com.snowplowanalytics.iglu.client.IgluCirceClient
import com.snowplowanalytics.iglu.client.resolver.registries.RegistryLookup
import com.snowplowanalytics.iglu.core.{SchemaKey, SelfDescribingData}

import com.snowplowanalytics.snowplow.badrows.FailureDetails

import loaders.CollectorPayload
import utils.HttpClient
import utils.ConversionUtils._
import Adapter.Adapted

object GoogleAnalyticsAdapter {
  // models a translation between measurement protocol fields and the fields in Iglu schemas
  type Translation = Function1[String, Either[FailureDetails.AdapterFailure, FieldType]]

  /**
   * Case class holding the name of the field in the Iglu schemas as well as the necessary
   * translation between the original MP string and the typed value
   *
   * @param fieldName   name of the field in the Iglu schemas
   * @param translation going from the raw string in the MP payload to the typed data fit to be
   *                    incorporated in a schema
   */
  final case class KVTranslation(fieldName: String, translation: Translation)

  /**
   * Case class representing measurement protocol schema data
   *
   * @param schemaKey        key of the Iglu schema
   * @param translationTable mapping of measurement protocol field names to field names in Iglu
   *                         schemas
   */
  final case class MPData(schemaKey: SchemaKey, translationTable: Map[String, KVTranslation])

  // class hierarchy defined to type the measurement protocol payload
  sealed trait FieldType

  final case class StringType(s: String) extends FieldType

  final case class IntType(i: Int) extends FieldType

  final case class DoubleType(d: Double) extends FieldType

  final case class BooleanType(b: Boolean) extends FieldType
}

/**
 * Transforms a collector payload which conforms to a known version of the Google Analytics
 * protocol into raw events.
 */
case class GoogleAnalyticsAdapter(schemas: GoogleAnalyticsSchemas) extends Adapter {
  import GoogleAnalyticsAdapter._

  // for failure messages
  private val GaVendor = "com.google.analytics"
  private val Vendor = s"$GaVendor.measurement-protocol"
  private val ProtocolVersion = "v1"
  private val Protocol = s"$Vendor-$ProtocolVersion"

  private val PageViewHitType = "pageview"

  implicit val encodeFieldType: Encoder[FieldType] = new Encoder[FieldType] {
    def apply(f: FieldType): Json =
      f match {
        case StringType(s) => Json.fromString(s)
        case IntType(i) => Json.fromInt(i)
        case DoubleType(f) => Json.fromDoubleOrNull(f)
        case BooleanType(b) => Json.fromBoolean(b)
      }
  }

  // translations between string and the needed types in the measurement protocol Iglu schemas
  private val idTranslation: (String => KVTranslation) = (fieldName: String) =>
    KVTranslation(fieldName, (value: String) => StringType(value).asRight)
  private val intTranslation: (String => KVTranslation) = (fieldName: String) =>
    KVTranslation(
      fieldName,
      (value: String) =>
        stringToJInteger(value)
          .map(i => IntType(i.toInt))
          .leftMap(e => FailureDetails.AdapterFailure.InputData(fieldName, value.some, e))
    )
  private val twoDecimalsTranslation: (String => KVTranslation) = (fieldName: String) =>
    KVTranslation(
      fieldName,
      (value: String) =>
        stringToTwoDecimals(value)
          .map(DoubleType)
          .leftMap(e => FailureDetails.AdapterFailure.InputData(fieldName, value.some, e))
    )
  private val doubleTranslation: (String => KVTranslation) = (fieldName: String) =>
    KVTranslation(
      fieldName,
      (value: String) =>
        stringToDouble(value)
          .map(DoubleType)
          .leftMap(e => FailureDetails.AdapterFailure.InputData(fieldName, value.some, e))
    )
  private val booleanTranslation: (String => KVTranslation) = (fieldName: String) =>
    KVTranslation(
      fieldName,
      (value: String) =>
        stringToBoolean(value)
          .map(BooleanType)
          .leftMap(e => FailureDetails.AdapterFailure.InputData(fieldName, value.some, e))
    )

  // unstruct event mappings
  private[registry] val unstructEventData: Map[String, MPData] = Map(
    "pageview" -> MPData(
      schemas.pageViewSchemaKey,
      Map(
        "dl" -> idTranslation("documentLocationUrl"),
        "dh" -> idTranslation("documentHostName"),
        "dp" -> idTranslation("documentPath"),
        "dt" -> idTranslation("documentTitle")
      )
    ),
    "screenview" -> MPData(
      schemas.screenViewSchemaKey,
      Map("cd" -> idTranslation("screenName"))
    ),
    "event" -> MPData(
      schemas.eventSchemaKey,
      Map(
        "ec" -> idTranslation("category"),
        "ea" -> idTranslation("action"),
        "el" -> idTranslation("label"),
        "ev" -> intTranslation("value")
      )
    ),
    "transaction" -> MPData(
      schemas.transactionSchemaKey,
      Map(
        "ti" -> idTranslation("id"),
        "ta" -> idTranslation("affiliation"),
        "tr" -> twoDecimalsTranslation("revenue"),
        "ts" -> twoDecimalsTranslation("shipping"),
        "tt" -> twoDecimalsTranslation("tax"),
        "tcc" -> idTranslation("couponCode"),
        "cu" -> idTranslation("currencyCode")
      )
    ),
    "item" -> MPData(
      schemas.itemSchemaKey,
      Map(
        "ti" -> idTranslation("transactionId"),
        "in" -> idTranslation("name"),
        "ip" -> twoDecimalsTranslation("price"),
        "iq" -> intTranslation("quantity"),
        "ic" -> idTranslation("code"),
        "iv" -> idTranslation("category"),
        "cu" -> idTranslation("currencyCode")
      )
    ),
    "social" -> MPData(
      schemas.socialSchemaKey,
      Map(
        "sn" -> idTranslation("network"),
        "sa" -> idTranslation("action"),
        "st" -> idTranslation("actionTarget")
      )
    ),
    "exception" -> MPData(
      schemas.exceptionSchemaKey,
      Map(
        "exd" -> idTranslation("description"),
        "exf" -> booleanTranslation("isFatal")
      )
    ),
    "timing" -> MPData(
      schemas.timingSchemaKey,
      Map(
        "utc" -> idTranslation("userTimingCategory"),
        "utv" -> idTranslation("userTimingVariableName"),
        "utt" -> intTranslation("userTimingTime"),
        "utl" -> idTranslation("userTimingLabel"),
        "plt" -> intTranslation("pageLoadTime"),
        "dns" -> intTranslation("dnsTime"),
        "pdt" -> intTranslation("pageDownloadTime"),
        "rrt" -> intTranslation("redirectResponseTime"),
        "tcp" -> intTranslation("tcpConnectTime"),
        "srt" -> intTranslation("serverResponseTime"),
        "dit" -> intTranslation("domInteractiveTime"),
        "clt" -> intTranslation("contentLoadTime")
      )
    )
  )

  // flat context mappings
  private val contextData: Map[SchemaKey, Map[String, KVTranslation]] = {
    // pageview can be a context too
    val ct = unstructEventData(PageViewHitType) :: List(
      MPData(
        schemas.undocumentedSchemaKey,
        List("a", "jid", "gjid").map(e => e -> idTranslation(e)).toMap
      ),
      MPData(
        schemas.privateSchemaKey,
        (List("_v", "_u", "_gid").map(e => e -> idTranslation(e.tail)) ++
          List("_s", "_r").map(e => e -> intTranslation(e.tail))).toMap
      ),
      MPData(
        schemas.generalSchemaKey,
        Map(
          "v" -> idTranslation("protocolVersion"),
          "tid" -> idTranslation("trackingId"),
          "aip" -> booleanTranslation("anonymizeIp"),
          "ds" -> idTranslation("dataSource"),
          "qt" -> intTranslation("queueTime"),
          "z" -> idTranslation("cacheBuster")
        )
      ),
      MPData(
        schemas.userSchemaKey,
        Map("cid" -> idTranslation("clientId"), "uid" -> idTranslation("userId"))
      ),
      MPData(
        schemas.sessionSchemaKey,
        Map(
          "sc" -> idTranslation("sessionControl"),
          "uip" -> idTranslation("ipOverride"),
          "ua" -> idTranslation("userAgentOverride"),
          "geoid" -> idTranslation("geographicalOverride")
        )
      ),
      MPData(
        schemas.trafficSourceSchemaKey,
        Map(
          "dr" -> idTranslation("documentReferrer"),
          "cn" -> idTranslation("campaignName"),
          "cs" -> idTranslation("campaignSource"),
          "cm" -> idTranslation("campaignMedium"),
          "ck" -> idTranslation("campaignKeyword"),
          "cc" -> idTranslation("campaignContent"),
          "ci" -> idTranslation("campaignId"),
          "gclid" -> idTranslation("googleAdwordsId"),
          "dclid" -> idTranslation("googleDisplayAdsId")
        )
      ),
      MPData(
        schemas.systemInfoSchemaKey,
        Map(
          "sr" -> idTranslation("screenResolution"),
          "vp" -> idTranslation("viewportSize"),
          "de" -> idTranslation("documentEncoding"),
          "sd" -> idTranslation("screenColors"),
          "ul" -> idTranslation("userLanguage"),
          "je" -> booleanTranslation("javaEnabled"),
          "fl" -> idTranslation("flashVersion")
        )
      ),
      MPData(
        schemas.linkSchemaKey,
        Map("linkid" -> idTranslation("id"))
      ),
      MPData(
        schemas.appSchemaKey,
        Map(
          "an" -> idTranslation("name"),
          "aid" -> idTranslation("id"),
          "av" -> idTranslation("version"),
          "aiid" -> idTranslation("installerId")
        )
      ),
      MPData(
        schemas.productActionSchemaKey,
        Map(
          "pa" -> idTranslation("productAction"),
          "pal" -> idTranslation("productActionList"),
          "cos" -> intTranslation("checkoutStep"),
          "col" -> idTranslation("checkoutStepOption")
        )
      ),
      MPData(
        schemas.contentExperimentSchemaKey,
        Map("xid" -> idTranslation("id"), "xvar" -> idTranslation("variant"))
      ),
      MPData(
        schemas.hitSchemaKey,
        Map("t" -> idTranslation("type"), "ni" -> booleanTranslation("nonInteractionHit"))
      ),
      MPData(
        schemas.promotionActionSchemaKey,
        Map("promoa" -> idTranslation("promotionAction"))
      )
    )
    ct.map(d => d.schemaKey -> d.translationTable).toMap
  }

  // layer of indirection linking flat context fields to schemas
  private val fieldToSchemaMap: Map[String, SchemaKey] = contextData
    .flatMap { case (schema, trTable) => trTable.keys.map(_ -> schema) }

  // IF indicates that the value is in the field name
  private val valueInFieldNameIndicator = "IF"
  // composite context mappings
  private val compositeContextData: List[MPData] = List(
    MPData(
      schemas.productSchemaKey,
      Map(
        s"${valueInFieldNameIndicator}pr" -> intTranslation("index"),
        "prid" -> idTranslation("sku"),
        "prnm" -> idTranslation("name"),
        "prbr" -> idTranslation("brand"),
        "prca" -> idTranslation("category"),
        "prva" -> idTranslation("variant"),
        "prpr" -> twoDecimalsTranslation("price"),
        "prqt" -> intTranslation("quantity"),
        "prcc" -> idTranslation("couponCode"),
        "prps" -> intTranslation("position"),
        "cu" -> idTranslation("currencyCode")
      )
    ),
    MPData(
      schemas.productCustomDimensionSchemaKey,
      Map(
        s"${valueInFieldNameIndicator}prcd" -> intTranslation("productIndex"),
        s"${valueInFieldNameIndicator}cd" -> intTranslation("dimensionIndex"),
        "prcd" -> idTranslation("value")
      )
    ),
    MPData(
      schemas.productCustomMetricSchemaKey,
      Map(
        s"${valueInFieldNameIndicator}prcm" -> intTranslation("productIndex"),
        s"${valueInFieldNameIndicator}cm" -> intTranslation("metricIndex"),
        "prcm" -> intTranslation("value")
      )
    ),
    MPData(
      schemas.productImpressionListSchemaKey,
      Map(
        s"${valueInFieldNameIndicator}il" -> intTranslation("index"),
        "ilnm" -> idTranslation("name")
      )
    ),
    MPData(
      schemas.productImpressionSchemaKey,
      Map(
        s"${valueInFieldNameIndicator}ilpi" -> intTranslation("listIndex"),
        s"${valueInFieldNameIndicator}pi" -> intTranslation("productIndex"),
        "ilpiid" -> idTranslation("sku"),
        "ilpinm" -> idTranslation("name"),
        "ilpibr" -> idTranslation("brand"),
        "ilpica" -> idTranslation("category"),
        "ilpiva" -> idTranslation("variant"),
        "ilpips" -> intTranslation("position"),
        "ilpipr" -> twoDecimalsTranslation("price"),
        "cu" -> idTranslation("currencyCode")
      )
    ),
    MPData(
      schemas.productImpressionCustomDimensionSchemaKey,
      Map(
        s"${valueInFieldNameIndicator}ilpicd" -> intTranslation("listIndex"),
        s"${valueInFieldNameIndicator}picd" -> intTranslation("productIndex"),
        s"${valueInFieldNameIndicator}cd" -> intTranslation("customDimensionIndex"),
        "ilpicd" -> idTranslation("value")
      )
    ),
    MPData(
      schemas.productImpressionCustomMetricSchemaKey,
      Map(
        s"${valueInFieldNameIndicator}ilpicm" -> intTranslation("listIndex"),
        s"${valueInFieldNameIndicator}picm" -> intTranslation("productIndex"),
        s"${valueInFieldNameIndicator}cm" -> intTranslation("customMetricIndex"),
        "ilpicm" -> intTranslation("value")
      )
    ),
    MPData(
      schemas.promotionSchemaKey,
      Map(
        s"${valueInFieldNameIndicator}promo" -> intTranslation("index"),
        "promoid" -> idTranslation("id"),
        "promonm" -> idTranslation("name"),
        "promocr" -> idTranslation("creative"),
        "promops" -> idTranslation("position")
      )
    ),
    MPData(
      schemas.customDimensionSchemaKey,
      Map(
        s"${valueInFieldNameIndicator}cd" -> intTranslation("index"),
        "cd" -> idTranslation("value")
      )
    ),
    MPData(
      schemas.customMetricSchemaKey,
      Map(
        s"${valueInFieldNameIndicator}cm" -> intTranslation("index"),
        "cm" -> doubleTranslation("value")
      )
    ),
    MPData(
      schemas.contentGroupSchemaKey,
      Map(
        s"${valueInFieldNameIndicator}cg" -> intTranslation("index"),
        "cg" -> idTranslation("value")
      )
    )
  )

  // List of schemas for which we need to re attach the currency
  private val compositeContextsWithCU: List[SchemaKey] =
    compositeContextData.filter(_.translationTable.contains("cu")).map(_.schemaKey)

  // mechanism used to filter out composite contexts that might have been built unnecessarily
  // e.g. if the field cd is in the payload it can be a screen name or a custom dimension
  // it can only be a custom dimension if the field is in the form cd12 which maps to two fields:
  // IFcd -> 12 and cd -> value, as a result it can be a custom dimension if there are more fields
  // than there are IF fields in the constructed map
  // This map holds the number of IF fields in the composite context mappings to ease the check
  // described above
  private val nrCompFieldsPerSchema: Map[SchemaKey, Int] =
    compositeContextData.map { d =>
      d.schemaKey -> d.translationTable.count(_._1.startsWith(valueInFieldNameIndicator))
    }.toMap

  // direct mappings between the measurement protocol and the snowplow tracker protocol
  private val directMappings: (String => Map[String, String]) = (hitType: String) =>
    Map(
      "uip" -> "ip",
      "dr" -> "refr",
      "de" -> "cs",
      "sd" -> "cd",
      "ul" -> "lang",
      "je" -> "f_java",
      "dl" -> "url",
      "dt" -> "page",
      "ti" -> (if (hitType == "transaction") "tr_id" else "ti_id"),
      "ta" -> "tr_af",
      "tr" -> "tr_tt",
      "ts" -> "tr_sh",
      "tt" -> "tr_tx",
      "in" -> "ti_nm",
      "ip" -> "ti_pr",
      "iq" -> "ti_qu",
      "ic" -> "ti_sk",
      "iv" -> "ti_ca",
      "cu" -> (if (hitType == "transaction") "tr_cu" else "ti_cu"),
      "ua" -> "ua"
    )

  /**
   * Converts a CollectorPayload instance of (possibly multiple) Google Analytics payloads into raw
   * events.
   * @param payload The CollectorPaylod containing one or more raw Google Analytics payloads as
   * collected by a Snowplow collector
   * @param client The Iglu client used for schema lookup and validation
   * @return a Validation boxing either a NEL of RawEvents on Success, or a NEL of Failure Strings
   */
  override def toRawEvents[F[_]: Monad: RegistryLookup: Clock: HttpClient](
    payload: CollectorPayload,
    client: IgluCirceClient[F]
  ): F[Adapted] = {
    val events: Option[NonEmptyList[ValidatedNel[FailureDetails.AdapterFailure, RawEvent]]] = for {
      body <- payload.body
      _ = client
      rawEvents <- body.linesIterator
                     .map[ValidatedNel[FailureDetails.AdapterFailure, RawEvent]]((bodyPart: String) => parsePayload(bodyPart, payload))
                     .toList
                     .toNel
    } yield rawEvents

    events match {
      case Some(rawEvents) =>
        Monad[F].pure(rawEvents.sequence)
      case None =>
        val failure =
          FailureDetails.AdapterFailure.InputData("body", None, "empty body")
        Monad[F].pure(failure.invalidNel)
    }
  }

  /**
   * Parses one Google Analytics payload.
   * @param bodyPart part of the payload's body corresponding to one Google Analytics payload
   * @param payload original CollectorPayload
   * @return a Validation boxing either a RawEvent or a NEL of Failure Strings
   */
  private def parsePayload(bodyPart: String, payload: CollectorPayload): ValidatedNel[FailureDetails.AdapterFailure, RawEvent] =
    (for {
      params <- parseUrlEncodedForm(bodyPart)
                  .leftMap(e =>
                    NonEmptyList
                      .one(FailureDetails.AdapterFailure.InputData("body", bodyPart.some, e))
                  )
      hitType <- params.get("t") match {
                   case Some(Some(t)) => Right(t)
                   case _ =>
                     val msg = "no t parameter provided: cannot determine hit type"
                     Left(
                       NonEmptyList
                         .one(FailureDetails.AdapterFailure.InputData("body", bodyPart.some, msg))
                     )
                 }
      // direct mappings
      mappings = translatePayload(params, directMappings(hitType))
      translationTable = unstructEventData
                           .get(hitType)
                           .map(_.translationTable)
                           .toValidNel(
                             FailureDetails.AdapterFailure
                               .InputData("t", hitType.some, "no matching hit type")
                           )
      schemaVal = lookupSchema(
                    hitType.some,
                    unstructEventData.mapValues(_.schemaKey)
                  ).toValidatedNel
      simpleContexts = buildContexts(params, contextData, fieldToSchemaMap)
      compositeContexts = buildCompositeContexts(
                            params,
                            compositeContextData,
                            compositeContextsWithCU,
                            nrCompFieldsPerSchema,
                            valueInFieldNameIndicator
                          ).toValidatedNel
      // better-monadic-for doesn't work for some reason?
      result <- (
                    translationTable,
                    schemaVal,
                    simpleContexts,
                    compositeContexts
                ).mapN { (trTable, schema, contexts, compContexts) =>
                  val contextJsons = (contexts.toList ++ compContexts)
                    .collect {
                      // an unnecessary pageview context might have been built so we need to remove it
                      case (s, d) if hitType != PageViewHitType || s != unstructEventData(PageViewHitType).schemaKey =>
                        SelfDescribingData(s, d.asJson)
                    }
                  val contextParam: Map[String, Option[String]] =
                    if (contextJsons.isEmpty) Map.empty
                    else Map("co" -> Some(toContexts(contextJsons).noSpaces))
                  (trTable, schema, contextParam)
                }.toEither
      payload <- translatePayload(params, result._1)
                   .map { e =>
                     val unstructEvent = toUnstructEvent(SelfDescribingData(result._2, e.asJson)).noSpaces
                     RawEvent(
                       api = payload.api,
                       parameters = result._3 ++ mappings ++
                         Map("e" -> Some("ue"), "ue_pr" -> Some(unstructEvent), "tv" -> Some(Protocol), "p" -> Some("srv")),
                       contentType = payload.contentType,
                       source = payload.source,
                       context = payload.context
                     )
                   }
                   .leftMap(NonEmptyList.one)
    } yield payload).toValidated

  /**
   * Translates a payload according to a translation table.
   * @param originalParams original payload in key-value format
   * @param translationTable mapping between original params and the wanted format
   * @return a translated params
   */
  private def translatePayload(
    originalParams: Map[String, Option[String]],
    translationTable: Map[String, KVTranslation]
  ): Either[FailureDetails.AdapterFailure, Map[String, FieldType]] = {
    val m = originalParams
      .collect { case (k, Some(v)) => (k, v) }
      .foldLeft(Map.empty[String, Either[FailureDetails.AdapterFailure, FieldType]]) {
        case (m, (fieldName, value)) =>
          translationTable
            .get(fieldName)
            .map {
              case KVTranslation(newName, translation) =>
                m + (newName -> translation(value))
            }
            .getOrElse(m)
      }
    traverseMap(m)
  }

  /**
   * Translates a payload according to a translation table.
   * @param originalParams original payload in key-value format
   * @param translationTable mapping between original params and the wanted format
   * @return a translated params
   */
  private def translatePayload(
    originalParams: Map[String, Option[String]],
    translationTable: Map[String, String]
  ): Map[String, Option[String]] =
    originalParams.foldLeft(Map.empty[String, Option[String]]) {
      case (m, (fieldName, value)) =>
        translationTable
          .get(fieldName)
          .map(newName => m + (newName -> value))
          .getOrElse(m)
    }

  /**
   * Discovers the contexts in the payload in linear time (size of originalParams).
   * @param originalParams original payload in key-value format
   * @param referenceTable map of context schemas and their associated translations
   * @param fieldToSchemaMap reverse indirection from referenceTable linking fields with the MP
   * nomenclature to schemas
   * @return a map containing the discovered contexts keyed by schema
   */
  private def buildContexts(
    originalParams: Map[String, Option[String]],
    referenceTable: Map[SchemaKey, Map[String, KVTranslation]],
    fieldToSchemaMap: Map[String, SchemaKey]
  ): ValidatedNel[FailureDetails.AdapterFailure, Map[SchemaKey, Map[String, FieldType]]] = {
    val m = originalParams
      .collect { case (k, Some(v)) => (k, v) }
      .foldLeft(
        Map.empty[SchemaKey, Map[String, ValidatedNel[FailureDetails.AdapterFailure, FieldType]]]
      ) {
        case (m, (fieldName, value)) =>
          fieldToSchemaMap
            .get(fieldName)
            .map { schema =>
              // this is safe when fieldToSchemaMap is built from referenceTable
              val KVTranslation(newName, translation) = referenceTable(schema)(fieldName)
              val trTable = m.getOrElse(schema, Map.empty) +
                (newName -> translation(value).toValidatedNel)
              m + (schema -> trTable)
            }
            .getOrElse(m)
      }
      .map { case (k, v) => (k -> traverseMap(v)) }
    traverseMap(m)
  }

  /**
   * Builds the contexts containing composite fields in quadratic time
   * (nr of composite fields * avg size for each of them)
   * @param originalParams original payload in key-value format
   * @param referenceTable list of context schemas containing composite fields and their
   * associated translations
   * @param schemasWithCU list of schemas that contain a currency code field
   * @param nrCompFieldsPerSchema map containing the number of field values in the composite field
   * name. Used to filter out contexts that might have been erroneously built.
   * @param indicator indicator used to determine if a key-value has been extracted from the
   * composite field name
   * @return a map containing the composite contexts keyed by schema
   */
  private def buildCompositeContexts(
    originalParams: Map[String, Option[String]],
    referenceTable: List[MPData],
    schemasWithCU: List[SchemaKey],
    nrCompFieldsPerSchema: Map[SchemaKey, Int],
    indicator: String
  ): Either[FailureDetails.AdapterFailure, List[(SchemaKey, Map[String, FieldType])]] =
    for {
      // composite params have digits in their key
      composite <- originalParams
                     .collect { case (k, Some(v)) => (k, v) }
                     .filterKeys(k => k.exists(_.isDigit))
                     .asRight
      brokenDown <- composite.toList.sorted.map {
                      case (k, v) => breakDownCompField(k, v, indicator)
                    }.sequence
      partitioned = brokenDown.map(_.partition(_._1.startsWith(indicator))).unzip
      // we additionally make sure we have a rectangular dataset
      grouped = (partitioned._2 ++ removeConsecutiveDuplicates(partitioned._1)).flatten
                  .groupBy(_._1)
                  .mapValues(_.map(_._2))
      translated <- {
        val m = grouped
          .foldLeft(
            Map.empty[SchemaKey, Map[String, Either[FailureDetails.AdapterFailure, Seq[FieldType]]]]
          ) {
            case (m, (fieldName, values)) =>
              val additions = referenceTable
                .filter(_.translationTable.contains(fieldName))
                .map { d =>
                  // this is safe because of the filter above
                  val KVTranslation(newName, translation) = d.translationTable(fieldName)
                  val trTable = m.getOrElse(d.schemaKey, Map.empty) +
                    (newName -> values.map(v => translation(v)).sequence)
                  d.schemaKey -> trTable
                }
                .toMap
              m ++ additions
          }
          .map { case (k, v) => (k -> traverseMap(v)) }
        traverseMap(m)
      }
      // we need to reattach the currency code to the contexts which need it
      transposed = translated.map {
                     case (k, m) =>
                       val values = transpose(m.values.map(_.toList).toList)
                       k -> (originalParams.get("cu") match {
                         case Some(Some(currency)) if schemasWithCU.contains(k) =>
                           values
                             .map(m.keys zip _)
                             .map(l => ("currencyCode" -> StringType(currency) :: l.toList).toMap)
                         case _ =>
                           values.map(m.keys zip _).map(_.toMap)
                       })
                   }
      // we need to filter out composite contexts which might have been built unnecessarily
      // eg due to ${indicator}pr being in 3 different schemas
      // + 1/0 depending on the presence of currencyCode
      filtered = transposed
                   .map {
                     case (k, vs) =>
                       val minSize = nrCompFieldsPerSchema(k)
                       k -> vs.filter(fs => fs.size > minSize + fs.get("currencyCode").foldMap(_ => 1))
                   }
                   .filter(_._2.nonEmpty)
      flattened = filtered.toList.flatMap { case (k, vs) => vs.map(k -> _) }
    } yield flattened

  /**
   * Breaks down measurement protocol composite fields into a small deterministic payload.
   * Two cases are possible:
   *   - the composite field name ends with a value
   *      e.g. pr12 -> val in this case the payload becomes Map(indicatorpr -> 12, pr -> val)
   *   - the composite field name ends with a sub field name
   *      e.g. pr12id -> val in this case the payload becomes Map(indicatorpr -> 12, prid -> val)
   * @param fieldName raw composite field name
   * @param value of the composite field
   * @param indicator string used to notify that this extracted key-value pair was from the
   * original composite field name
   * @return a mini payload extracted from the composite field or a failure
   */
  private[registry] def breakDownCompField(
    fieldName: String,
    value: String,
    indicator: String
  ): Either[FailureDetails.AdapterFailure, Map[String, String]] =
    for {
      brokenDown <- breakDownCompField(fieldName)
      (strs, ints) = brokenDown
      m <- if (strs.length == ints.length)
             (strs.scanRight("")(_ + _).init.map(indicator + _) zip ints).toMap.asRight
           else if (strs.length == ints.length + 1)
             (strs.init.scanRight("")(_ + _).init.map(indicator + _) zip ints).toMap.asRight
           else {
             // can't happen without changing breakDownCompField(fieldName)
             val msg = "cannot parse composite field name: unexpected number of values"
             FailureDetails.AdapterFailure.InputData(fieldName, value.some, msg).asLeft
           }
      r = m + (strs.reduce(_ + _) -> value)
    } yield r

  private val compositeFieldRegex =
    ("(" + List("pr", "promo", "il", "cd", "cm", "cg").mkString("|") + ")" +
      """(\d+)([a-zA-Z]*)(\d*)([a-zA-Z]*)(\d*)$""").r

  /**
   * Breaks down measurement protocol composite fields in a pair of list of strings.
   * e.g. abc12def45 becomes (List("abc", "def"), List("12", "45"))
   * @param fieldName raw composite field name
   * @return the break down of the field or a failure if it couldn't be parsed
   */
  private[registry] def breakDownCompField(fieldName: String): Either[FailureDetails.AdapterFailure, (List[String], List[String])] =
    fieldName match {
      case compositeFieldRegex(grps @ _*) => splitEvenOdd(grps.toList.filter(_.nonEmpty)).asRight
      case s if s.isEmpty =>
        FailureDetails.AdapterFailure
          .InputData(fieldName, none, "cannot parse empty field name")
          .asLeft
      case _ =>
        val msg = s"composite field name has to conform to regex $compositeFieldRegex"
        FailureDetails.AdapterFailure.InputData(fieldName, none, msg).asLeft
    }

  /** Splits a list in two based on the oddness or evenness of their indices */
  private def splitEvenOdd[T](list: List[T]): (List[T], List[T]) = {
    @tailrec
    def go(
      l: List[T],
      even: List[T],
      odd: List[T]
    ): (List[T], List[T], List[T]) =
      l match {
        case h1 :: h2 :: t => go(t, h1 :: even, h2 :: odd)
        case h :: Nil => (Nil, h :: even, odd)
        case Nil => (Nil, even, odd)
      }
    val res = go(list, Nil, Nil)
    (res._2.reverse, res._3.reverse)
  }

  /** Removes subsequent duplicates, e.g. List(1, 1, 2, 2, 3, 3, 1) becomes List(1, 2, 3, 1) */
  private def removeConsecutiveDuplicates[T](list: List[T]): List[T] =
    list
      .foldLeft(List.empty[T]) {
        case (h :: t, e) if e != h => e :: h :: t
        case (Nil, e) => e :: Nil
        case (l, _) => l
      }
      .reverse

  /** Transposes a list of lists, does not need to be rectangular unlike the stdlib's version. */
  private def transpose[T](l: List[List[T]]): List[List[T]] =
    l.flatMap(_.headOption) match {
      case Nil => Nil
      case head => head :: transpose(l.collect { case _ :: tail => tail })
    }

  private def traverseMap[G[_]: Functor: Applicative, K, V](m: Map[K, G[V]]): G[Map[K, V]] =
    m.toList
      .traverse {
        case (name, vnel) =>
          vnel.map(m => (name, m))
      }
      .map(_.toMap)
}
