/*Copyright (c) 2012-2022 Snowplow Analytics Ltd. All rights reserved.
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

import cats.Monad
import cats.data.{EitherT, NonEmptyList, ValidatedNel}
import cats.implicits._
import com.snowplowanalytics.iglu.core.{SchemaCriterion, SchemaKey, SchemaVer, SelfDescribingData}
import com.snowplowanalytics.lrumap.{CreateLruMap, LruMap}
import com.snowplowanalytics.snowplow.badrows.FailureDetails
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.EnrichmentConf.UaParserConf
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.UaParserEnrichment.UserAgentCache
import com.snowplowanalytics.snowplow.enrich.common.utils.CirceUtils
import io.circe.Json
import io.circe.syntax._
import ua_parser.{Client, Parser}

import java.io.{FileInputStream, InputStream}
import java.net.URI

/** Companion object. Lets us create a UaParserEnrichment from a Json. */
object UaParserEnrichment extends ParseableEnrichment {
  override val supportedSchema =
    SchemaCriterion("com.snowplowanalytics.snowplow", "ua_parser_config", "jsonschema", 1, 0)
  val Schema = SchemaKey(
    "com.snowplowanalytics.snowplow",
    "ua_parser_context",
    "jsonschema",
    SchemaVer.Full(1, 0, 0)
  )

  private val localFile = "./ua-parser-rules.yml"
  type UserAgentCache[F[_]] = LruMap[F, String, SelfDescribingData[Json]]

  override def parse(
    c: Json,
    schemaKey: SchemaKey,
    localMode: Boolean = false
  ): ValidatedNel[String, UaParserConf] =
    (for {
      _ <- isParseable(c, schemaKey).leftMap(NonEmptyList.one)
      rules <- getCustomRules(c).toEither
    } yield UaParserConf(schemaKey, rules)).toValidated

  /**
   * Creates a UaParserEnrichment from a UaParserConf
   * @param conf Configuration for the ua parser enrichment
   * @return a ua parser enrichment
   */
  def apply[F[_]: Monad: CreateUaParserEnrichment](conf: UaParserConf): EitherT[F, String, UaParserEnrichment[F]] =
    EitherT(CreateUaParserEnrichment[F].create(conf))

  private def getCustomRules(conf: Json): ValidatedNel[String, Option[(URI, String)]] =
    if (conf.hcursor.downField("parameters").downField("uri").focus.isDefined)
      (for {
        uriAndDb <- (
                        CirceUtils.extract[String](conf, "parameters", "uri").toValidatedNel,
                        CirceUtils.extract[String](conf, "parameters", "database").toValidatedNel
                    ).mapN((_, _)).toEither
        source <- getDatabaseUri(uriAndDb._1, uriAndDb._2).leftMap(NonEmptyList.one)
      } yield (source, localFile)).toValidated.map(_.some)
    else
      None.validNel
}

/** Config for an ua_parser_config enrichment. Uses uap-java library to parse client attributes */
final case class UaParserEnrichment[F[_]: Monad](
  schemaKey: SchemaKey,
  parser: Parser,
  userAgentCache: UserAgentCache[F]
) extends Enrichment {
  private val enrichmentInfo = FailureDetails.EnrichmentInformation(schemaKey, "ua-parser").some

  /** Adds a period in front of a not-null version element */
  def prependDot(versionElement: String): String =
    if (versionElement != null)
      "." + versionElement
    else
      ""

  /** Prepends space before the versionElement */
  def prependSpace(versionElement: String): String =
    if (versionElement != null)
      " " + versionElement
    else
      ""

  /** Checks for null value in versionElement for family parameter */
  def checkNull(versionElement: String): String =
    if (versionElement == null)
      ""
    else
      versionElement

  /**
   * Extracts the client attributes from a useragent string, using UserAgentEnrichment.
   * @param useragent to extract from. Should be encoded, i.e. not previously decoded.
   * @return the json or the message of the bad row details
   */
  def extractUserAgent(useragent: String): F[Either[FailureDetails.EnrichmentFailure, SelfDescribingData[Json]]] =
    userAgentCache.get(useragent).flatMap {
      case Some(cachedValue) =>
        Monad[F].pure(Right(cachedValue))
      case None =>
        evaluateUserAgentContext(useragent)
          .pure[F]
          .flatTap {
            case Right(context) =>
              userAgentCache.put(useragent, context)
            case Left(_) =>
              Monad[F].unit
          }
    }

  private def evaluateUserAgentContext(useragent: String): Either[FailureDetails.EnrichmentFailure, SelfDescribingData[Json]] =
    Either
      .catchNonFatal(parser.parse(useragent))
      .leftMap { e =>
        val msg = s"could not parse useragent: ${e.getMessage}"
        val f = FailureDetails.EnrichmentFailureMessage.InputData(
          "useragent",
          useragent.some,
          msg
        )
        FailureDetails.EnrichmentFailure(enrichmentInfo, f)
      }
      .map(assembleContext)

  /** Assembles ua_parser_context from a parsed user agent. */
  def assembleContext(c: Client): SelfDescribingData[Json] = {
    // To display useragent version
    val useragentVersion = checkNull(c.userAgent.family) + prependSpace(c.userAgent.major) + prependDot(
      c.userAgent.minor
    ) + prependDot(c.userAgent.patch)

    // To display operating system version
    val osVersion = checkNull(c.os.family) + prependSpace(c.os.major) + prependDot(c.os.minor) +
      prependDot(c.os.patch) + prependDot(c.os.patchMinor)

    def getJson(s: String): Json =
      Option(s).map(Json.fromString).getOrElse(Json.Null)

    SelfDescribingData(
      UaParserEnrichment.Schema,
      Json.obj(
        "useragentFamily" := getJson(c.userAgent.family),
        "useragentMajor" := getJson(c.userAgent.major),
        "useragentMinor" := getJson(c.userAgent.minor),
        "useragentPatch" := getJson(c.userAgent.patch),
        "useragentVersion" := getJson(useragentVersion),
        "osFamily" := getJson(c.os.family),
        "osMajor" := getJson(c.os.major),
        "osMinor" := getJson(c.os.minor),
        "osPatch" := getJson(c.os.patch),
        "osPatchMinor" := getJson(c.os.patchMinor),
        "osVersion" := getJson(osVersion),
        "deviceFamily" := getJson(c.device.family)
      )
    )
  }
}

trait CreateUaParserEnrichment[F[_]] {
  def create(conf: UaParserConf): F[Either[String, UaParserEnrichment[F]]]
}

object CreateUaParserEnrichment {
  def apply[F[_]](implicit ev: CreateUaParserEnrichment[F]): CreateUaParserEnrichment[F] = ev

  implicit def createUaParser[F[_]: Monad](implicit CLM: CreateLruMap[F, String, SelfDescribingData[Json]]): CreateUaParserEnrichment[F] =
    new CreateUaParserEnrichment[F] {
      def create(conf: UaParserConf): F[Either[String, UaParserEnrichment[F]]] =
        CLM.create(5000).map { cache =>
          initializeParser(conf.uaDatabase.map(_._2)).map { parser =>
            UaParserEnrichment(conf.schemaKey, parser, cache)
          }
        }
    }

  private def initializeParser(file: Option[String]): Either[String, Parser] =
    (for {
      input <- Either.catchNonFatal(file.map(new FileInputStream(_)))
      parser <- Either.catchNonFatal(createParser(input))
    } yield parser).leftMap(e => s"Failed to initialize ua parser: [${e.getMessage}]")

  private def createParser(input: Option[InputStream]): Parser =
    input match {
      case Some(is) =>
        try new Parser(is)
        finally is.close()
      case None => new Parser()
    }
}
