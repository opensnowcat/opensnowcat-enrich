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

import cats.data.{NonEmptyList, ValidatedNel}
import cats.implicits._

import io.circe._

import org.apache.commons.codec.digest.DigestUtils

import com.snowplowanalytics.iglu.core.{SchemaCriterion, SchemaKey}

import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.EnrichmentConf.EventFingerprintConf
import com.snowplowanalytics.snowplow.enrich.common.utils.CirceUtils
import com.snowplowanalytics.snowplow.enrich.common.`package`.RawEventParameters

/** Lets us create an EventFingerprintEnrichment from a Json. */
object EventFingerprintEnrichment extends ParseableEnrichment {
  override val supportedSchema =
    SchemaCriterion(
      "com.snowplowanalytics.snowplow",
      "event_fingerprint_config",
      "jsonschema",
      1,
      0
    )

  private val UnitSeparator = "\u001f"

  /**
   * Creates an EventFingerprintEnrichment instance from a Json.
   * @param c The enrichment JSON
   * @param schemaKey provided for the enrichment, must be supported fby this enrichment
   * @return a EventFingerprintEnrichment configuration
   */
  override def parse(
    c: Json,
    schemaKey: SchemaKey,
    localMode: Boolean = false
  ): ValidatedNel[String, EventFingerprintConf] =
    (for {
      _ <- isParseable(c, schemaKey).leftMap(e => NonEmptyList.one(e))
      // better-monadic-for
      paramsAndAlgo <- (
                           CirceUtils.extract[List[String]](c, "parameters", "excludeParameters").toValidatedNel,
                           CirceUtils.extract[String](c, "parameters", "hashAlgorithm").toValidatedNel
                       ).mapN((_, _)).toEither
      algorithm <- getAlgorithm(paramsAndAlgo._2)
                     .leftMap(e => NonEmptyList.one(e))
    } yield EventFingerprintConf(schemaKey, algorithm, paramsAndAlgo._1)).toValidated

  /**
   * Look up the fingerprinting algorithm by name
   * @param algorithmName
   * @return A hashing algorithm
   */
  private[registry] def getAlgorithm(algorithmName: String): Either[String, String => String] =
    algorithmName match {
      case "MD5" => ((s: String) => DigestUtils.md5Hex(s)).asRight
      case "SHA1" => ((s: String) => DigestUtils.sha1Hex(s)).asRight
      case "SHA256" => ((s: String) => DigestUtils.sha256Hex(s)).asRight
      case "SHA384" => ((s: String) => DigestUtils.sha384Hex(s)).asRight
      case "SHA512" => ((s: String) => DigestUtils.sha512Hex(s)).asRight
      case other =>
        s"[$other] is not a supported event fingerprint generation algorithm".asLeft
    }
}

/**
 * Config for an event fingerprint enrichment
 * @param algorithm Hashing algorithm
 * @param excludedParameters List of querystring parameters to exclude from the calculation
 * @return Event fingerprint
 */
final case class EventFingerprintEnrichment(algorithm: String => String, excludedParameters: List[String]) extends Enrichment {

  /**
   * Calculate an event fingerprint using all querystring fields except the excludedParameters
   * @return Event fingerprint
   */
  def getEventFingerprint(parameters: RawEventParameters): String = {
    val builder = new StringBuilder
    parameters.toList.collect { case (k, Some(v)) => (k, v) }.sortWith(_._1 < _._1).foreach {
      case (key, value) =>
        if (!excludedParameters.contains(key)) {
          builder.append(key)
          builder.append(EventFingerprintEnrichment.UnitSeparator)
          builder.append(value)
          builder.append(EventFingerprintEnrichment.UnitSeparator)
        }
    }
    algorithm(builder.toString)
  }
}
