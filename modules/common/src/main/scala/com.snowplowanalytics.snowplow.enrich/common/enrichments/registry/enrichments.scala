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

import cats.data.ValidatedNel
import cats.syntax.either._

import io.circe._

import com.snowplowanalytics.iglu.core.{SchemaCriterion, SchemaKey}

import com.snowplowanalytics.snowplow.enrich.common.utils.ConversionUtils

/** Trait inherited by every enrichment config case class */
trait Enrichment

/** Trait to hold helpers relating to enrichment config */
trait ParseableEnrichment {

  /** The schemas supported by this enrichment */
  def supportedSchema: SchemaCriterion

  /**
   * Tentatively parses an enrichment configuration and sends back the files that need to be cached
   * prior to the EnrichmentRegistry construction.
   * @param config Json configuration for the enrichment (not self-describing)
   * @param schemaKey Version of the schema we want to run
   * @param localMode whether to have an enrichment conf which will produce an enrichment running locally,
   * used for testing
   * @return the configuration for this enrichment as well as the list of files it needs cached
   */
  def parse(
    config: Json,
    schemaKey: SchemaKey,
    localMode: Boolean
  ): ValidatedNel[String, EnrichmentConf]

  /**
   * Tests whether a JSON is parseable by a specific EnrichmentConfig constructor
   * @param config The JSON
   * @param schemaKey The schemaKey which needs to be checked
   * @return The JSON or an error message, boxed
   */
  def isParseable(config: Json, schemaKey: SchemaKey): Either[String, Json] =
    if (supportedSchema.matches(schemaKey))
      config.asRight
    else
      (s"Schema key ${schemaKey.toSchemaUri} is not supported. A '${supportedSchema.name}' " +
        s"enrichment must have schema ${supportedSchema.asString}.").asLeft

  /**
   * Convert the path to a file from a String to a URI.
   * @param uri URI to a database file
   * @param database Name of the database
   * @return an Either-boxed URI
   */
  protected def getDatabaseUri(uri: String, database: String): Either[String, URI] =
    ConversionUtils
      .stringToUri(uri + (if (uri.endsWith("/")) "" else "/") + database)
      .flatMap {
        case Some(u) => u.asRight
        case None => "URI to IAB file must be provided".asLeft
      }
}
