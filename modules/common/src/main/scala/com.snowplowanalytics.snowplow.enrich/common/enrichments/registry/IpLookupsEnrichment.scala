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

import cats.data.{NonEmptyList, ValidatedNel}
import cats.implicits._

import cats.effect.{Async, Blocker, ContextShift}

import io.circe._

import inet.ipaddr.HostName

import com.snowplowanalytics.iglu.core.{SchemaCriterion, SchemaKey}

import com.snowplowanalytics.maxmind.iplookups._
import com.snowplowanalytics.maxmind.iplookups.model._

import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.EnrichmentConf.IpLookupsConf
import com.snowplowanalytics.snowplow.enrich.common.utils.CirceUtils

/** Companion object. Lets us create an IpLookupsEnrichment instance from a Json. */
object IpLookupsEnrichment extends ParseableEnrichment {
  override val supportedSchema =
    SchemaCriterion("com.snowplowanalytics.snowplow", "ip_lookups", "jsonschema", 2, 0)

  /**
   * Creates an IpLookupsConf from a Json.
   * @param c The ip_lookups enrichment JSON
   * @param schemaKey provided for the enrichment, must be supported  by this enrichment
   * @param localMode Whether to use the local MaxMind data file, enabled for tests
   * @return a IpLookups configuration
   */
  override def parse(
    c: Json,
    schemaKey: SchemaKey,
    localMode: Boolean
  ): ValidatedNel[String, IpLookupsConf] =
    isParseable(c, schemaKey)
      .leftMap(e => NonEmptyList.one(e))
      .flatMap { _ =>
        (
          getArgumentFromName(c, "geo").sequence,
          getArgumentFromName(c, "isp").sequence,
          getArgumentFromName(c, "domain").sequence,
          getArgumentFromName(c, "connectionType").sequence
        ).mapN { (geo, isp, domain, connection) =>
          IpLookupsConf(
            schemaKey,
            file(geo, localMode),
            file(isp, localMode),
            file(domain, localMode),
            file(connection, localMode)
          )
        }.toEither
      }
      .toValidated

  private def file(db: Option[IpLookupsDatabase], localMode: Boolean): Option[(URI, String)] =
    db.map { d =>
      if (localMode)
        (d.uri, Option(getClass.getResource(d.db)).getOrElse(getClass.getResource("/" + d.db)).toURI.getPath)
      else
        (d.uri, s"./ip_${d.name}")
    }

  /**
   * Creates the (URI, String) tuple arguments which are the case class parameters
   * @param conf The ip_lookups enrichment JSON
   * @param name The name of the lookup: "geo", "isp", "organization", "domain"
   * @return None if the database isn't being used, Some(Failure) if its URI is invalid,
   * Some(Success) if it is found
   */
  private def getArgumentFromName(conf: Json, name: String): Option[ValidatedNel[String, IpLookupsDatabase]] =
    if (conf.hcursor.downField("parameters").downField(name).focus.isDefined) {
      val uri = CirceUtils.extract[String](conf, "parameters", name, "uri")
      val db = CirceUtils.extract[String](conf, "parameters", name, "database")

      // better-monadic-for
      (for {
        uriAndDb <- (uri.toValidatedNel, db.toValidatedNel).mapN((_, _)).toEither
        uri <- getDatabaseUri(uriAndDb._1, uriAndDb._2).leftMap(NonEmptyList.one)
      } yield IpLookupsDatabase(name, uri, uriAndDb._2)).toValidated.some
    } else None

  def create[F[_]: Async: ContextShift](
    blocker: Blocker,
    geoFilePath: Option[String],
    ispFilePath: Option[String],
    domainFilePath: Option[String],
    connectionFilePath: Option[String]
  ): F[IpLookupsEnrichment[F]] =
    CreateIpLookups[F]
      .createFromFilenames(
        geoFilePath,
        ispFilePath,
        domainFilePath,
        connectionFilePath,
        memCache = true,
        lruCacheSize = 20000
      )
      .map(i => IpLookupsEnrichment(i, blocker))
}

/**
 * Contains enrichments based on IP address.
 * @param ipLookups IP lookups client
 * @param blocker Runs db lookups on a separate thread pool
 */
final case class IpLookupsEnrichment[F[_]: ContextShift](ipLookups: IpLookups[F], blocker: Blocker) extends Enrichment {

  /**
   * Extract the geo-location using the client IP address.
   * @param ip The client's IP address to use to lookup the client's geo-location
   * @return an IpLookupResult
   */
  def extractIpInformation(ip: String): F[IpLookupResult] =
    blocker.blockOn {
      ipLookups.performLookups(Either.catchNonFatal(new HostName(ip).toAddress).fold(_ => ip, addr => addr.toString))
    }
}

private[enrichments] final case class IpLookupsDatabase(
  name: String,
  uri: URI,
  db: String
)
