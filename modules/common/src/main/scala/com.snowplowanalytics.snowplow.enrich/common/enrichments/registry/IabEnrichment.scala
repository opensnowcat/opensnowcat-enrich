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

import java.io.File
import java.net.{InetAddress, URI}

import cats.{Id, Monad}
import cats.data.{NonEmptyList, ValidatedNel}

import cats.effect.Sync
import cats.implicits._

import org.joda.time.DateTime

import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._

import com.snowplowanalytics.iglu.core.{SchemaCriterion, SchemaKey, SchemaVer, SelfDescribingData}

import com.snowplowanalytics.iab.spidersandrobotsclient.IabClient
import com.snowplowanalytics.snowplow.badrows.FailureDetails

import com.snowplowanalytics.snowplow.enrich.common.utils.CirceUtils
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.EnrichmentConf.IabConf

/** Companion object. Lets us create an IabEnrichment instance from a Json. */
object IabEnrichment extends ParseableEnrichment {
  override val supportedSchema = SchemaCriterion(
    "com.snowplowanalytics.snowplow.enrichments",
    "iab_spiders_and_robots_enrichment",
    "jsonschema",
    1,
    0
  )

  /**
   * Creates an IabConf from a Json.
   * @param c The iab_spiders_and_robots_enrichment JSON
   * @param schemaKey provided for the enrichment, must be supported by this enrichment
   * @param localMode Whether to use the local IAB database file, enabled for tests
   * @return a Iab configuration
   */
  override def parse(
    c: Json,
    schemaKey: SchemaKey,
    localMode: Boolean
  ): ValidatedNel[String, IabConf] =
    isParseable(c, schemaKey)
      .leftMap(e => NonEmptyList.one(e))
      .flatMap { _ =>
        (
          getIabDbFromName(c, "ipFile"),
          getIabDbFromName(c, "excludeUseragentFile"),
          getIabDbFromName(c, "includeUseragentFile")
        ).mapN { (ip, exclude, include) =>
          IabConf(
            schemaKey,
            file(ip, localMode),
            file(exclude, localMode),
            file(include, localMode)
          )
        }.toEither
      }
      .toValidated

  private def file(db: IabDatabase, localMode: Boolean): (URI, String) =
    if (localMode)
      (db.uri, Option(getClass.getResource(db.db)).getOrElse(getClass.getResource("/" + db.db)).toURI.getPath)
    else
      (db.uri, s"./iab_${db.name}")

  /**
   * Creates an IabEnrichment from a IabConf
   * @param conf Configuration for the iab enrichment
   * @return an iab enrichment
   */
  def apply[F[_]: Monad: CreateIabClient](conf: IabConf): F[IabEnrichment] =
    CreateIabClient[F]
      .create(conf.ipFile._2, conf.excludeUaFile._2, conf.includeUaFile._2)
      .map(c => IabEnrichment(conf.schemaKey, c))

  /**
   * Creates IabDatabase instances used in the IabEnrichment case class.
   * @param config The iab_spiders_and_robots_enrichment JSON
   * @param name of the field, e.g. "ipFile", "excluseUseragentFile", "includeUseragentFile"
   * @return None if the field does not exist, Some(Failure) if the URI is invalid, Some(Success) if
   * it is found
   */
  private def getIabDbFromName(config: Json, name: String): ValidatedNel[String, IabDatabase] = {
    val uri = CirceUtils.extract[String](config, "parameters", name, "uri")
    val db = CirceUtils.extract[String](config, "parameters", name, "database")

    // better-monadic-for
    (for {
      uriAndDb <- (uri.toValidatedNel, db.toValidatedNel).mapN((_, _)).toEither
      uri <- getDatabaseUri(uriAndDb._1, uriAndDb._2).leftMap(NonEmptyList.one)
    } yield IabDatabase(name, uri, uriAndDb._2)).toValidated
  }
}

/**
 * Contains enrichments based on IAB Spiders&Robots lookup.
 * @param schemaKey enrichment's static Iglu Schema Key
 * @param iabClient worker object
 */
final case class IabEnrichment(schemaKey: SchemaKey, iabClient: IabClient) extends Enrichment {
  val outputSchema =
    SchemaKey("com.iab.snowplow", "spiders_and_robots", "jsonschema", SchemaVer.Full(1, 0, 0))
  private val enrichmentInfo =
    FailureDetails.EnrichmentInformation(schemaKey, "iab-spiders-and-robots").some

  /**
   * Get the IAB response containing information about whether an event is a spider or robot using
   * the IAB client library.
   * @param userAgent User agent used to perform the check
   * @param ipAddress IP address used to perform the check
   * @param accurateAt Date of the event, used to determine whether entries in the IAB list are
   * relevant or outdated
   * @return an IabResponse object
   */
  private[enrichments] def performCheck(
    userAgent: String,
    ipAddress: InetAddress,
    accurateAt: DateTime
  ): Either[FailureDetails.EnrichmentFailure, IabEnrichmentResponse] =
    Either
      .catchNonFatal(iabClient.checkAt(userAgent, ipAddress, accurateAt.toDate)) match {
      case Right(result) =>
        IabEnrichmentResponse(
          result.isSpiderOrRobot,
          result.getCategory.toString,
          result.getReason.toString,
          result.getPrimaryImpact.toString
        ).asRight
      case Left(exception) =>
        val message = FailureDetails.EnrichmentFailureMessage.Simple(exception.getMessage)
        FailureDetails.EnrichmentFailure(enrichmentInfo, message).asLeft
    }

  /**
   * Get the IAB response as a JSON context for a specific event
   * @param userAgent enriched event optional user agent
   * @param ipAddress enriched event optional IP address
   * @param accurateAt enriched event optional datetime
   * @return IAB response as a self-describing JSON object
   */
  def getIabContext(
    userAgent: String,
    ipAddress: InetAddress,
    accurateAt: DateTime
  ): Either[FailureDetails.EnrichmentFailure, SelfDescribingData[Json]] =
    performCheck(userAgent, ipAddress, accurateAt).map { iab =>
      SelfDescribingData(outputSchema, iab.asJson)
    }
}

trait CreateIabClient[F[_]] {
  def create(
    ipFile: String,
    excludeUaFile: String,
    includeUaFile: String
  ): F[IabClient]
}

object CreateIabClient {
  def apply[F[_]](implicit ev: CreateIabClient[F]): CreateIabClient[F] = ev

  implicit def syncCreateIabClient[F[_]: Sync]: CreateIabClient[F] =
    new CreateIabClient[F] {
      def create(
        ipFile: String,
        excludeUaFile: String,
        includeUaFile: String
      ): F[IabClient] =
        Sync[F].delay {
          new IabClient(new File(ipFile), new File(excludeUaFile), new File(includeUaFile))
        }
    }

  implicit def idCreateIabClient: CreateIabClient[Id] =
    new CreateIabClient[Id] {
      def create(
        ipFile: String,
        excludeUaFile: String,
        includeUaFile: String
      ): Id[IabClient] =
        new IabClient(new File(ipFile), new File(excludeUaFile), new File(includeUaFile))
    }
}

/** Case class copy of `com.snowplowanalytics.iab.spidersandrobotsclient.IabResponse` */
private[enrichments] final case class IabEnrichmentResponse(
  spiderOrRobot: Boolean,
  category: String,
  reason: String,
  primaryImpact: String
)

/** Case class representing an IAB database location */
private[enrichments] final case class IabDatabase(
  name: String,
  uri: URI,
  db: String
)
