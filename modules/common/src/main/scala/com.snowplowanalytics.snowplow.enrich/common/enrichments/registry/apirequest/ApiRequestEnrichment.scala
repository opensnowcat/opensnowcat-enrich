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
package com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.apirequest

import cats.Monad
import cats.data.{EitherT, NonEmptyList, Validated, ValidatedNel}
import cats.effect.Clock
import cats.implicits._
import com.snowplowanalytics.iglu.core.circe.implicits._
import com.snowplowanalytics.iglu.core.{SchemaCriterion, SchemaKey, SelfDescribingData}
import com.snowplowanalytics.lrumap._
import com.snowplowanalytics.snowplow.badrows.FailureDetails
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.EnrichmentConf.ApiRequestConf
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.apirequest.ApiRequestEnrichment.ApiRequestEvaluator
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.{CachingEvaluator, Enrichment, ParseableEnrichment}
import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import com.snowplowanalytics.snowplow.enrich.common.utils.{CirceUtils, HttpClient}
import io.circe._
import io.circe.generic.auto._

import java.util.UUID

object ApiRequestEnrichment extends ParseableEnrichment {

  type ApiRequestEvaluator[F[_]] = CachingEvaluator[F, String, Json]

  override val supportedSchema =
    SchemaCriterion(
      "com.snowplowanalytics.snowplow.enrichments",
      "api_request_enrichment_config",
      "jsonschema",
      1,
      0
    )

  /**
   * Creates an ApiRequestEnrichment instance from a JValue.
   * @param c The enrichment JSON (not self-describing)
   * @param schemaKey The SchemaKey provided for the enrichment
   * Must be a supported SchemaKey for this enrichment
   * @return a configured ApiRequestEnrichment instance
   */
  override def parse(
    c: Json,
    schemaKey: SchemaKey,
    localMode: Boolean = false
  ): ValidatedNel[String, ApiRequestConf] =
    isParseable(c, schemaKey)
      .leftMap(e => NonEmptyList.one(e))
      .flatMap { _ =>
        // input ctor throws exception
        val inputs: ValidatedNel[String, List[Input]] = Either.catchNonFatal {
          CirceUtils.extract[List[Input]](c, "parameters", "inputs").toValidatedNel
        } match {
          case Left(e) => e.getMessage.invalidNel
          case Right(r) => r
        }
        (
          inputs,
          CirceUtils.extract[HttpApi](c, "parameters", "api", "http").toValidatedNel,
          CirceUtils.extract[List[Output]](c, "parameters", "outputs").toValidatedNel,
          CirceUtils.extract[Cache](c, "parameters", "cache").toValidatedNel,
          CirceUtils
            .extract[Option[Boolean]](c, "parameters", "ignoreOnError")
            .map {
              case Some(value) => value
              case None => false
            }
            .toValidatedNel
        ).mapN { (inputs, api, outputs, cache, ignoreOnError) =>
          ApiRequestConf(schemaKey, inputs, api, outputs, cache, ignoreOnError)
        }.toEither
      }
      .toValidated

  /**
   * Creates an UUID based on url and optional body.
   * @param url URL to query
   * @param body optional request body
   * @return UUID that identifies of the request.
   */
  def cacheKey(url: String, body: Option[String]): String = {
    val contentKey = url + body.getOrElse("")
    UUID.nameUUIDFromBytes(contentKey.getBytes).toString
  }

  def apply[F[_]: CreateApiRequestEnrichment](conf: ApiRequestConf): F[ApiRequestEnrichment[F]] =
    CreateApiRequestEnrichment[F].create(conf)
}

final case class ApiRequestEnrichment[F[_]: Monad: HttpClient: Clock](
  schemaKey: SchemaKey,
  inputs: List[Input],
  api: HttpApi,
  outputs: List[Output],
  apiRequestEvaluator: ApiRequestEvaluator[F],
  ignoreOnError: Boolean
) extends Enrichment {
  import ApiRequestEnrichment._

  private val enrichmentInfo =
    FailureDetails.EnrichmentInformation(schemaKey, "api-request").some

  /**
   * Primary function of the enrichment. Failure means HTTP failure, failed unexpected JSON-value,
   * etc. Successful None skipped lookup (missing key for eg.)
   * @param event currently enriching event
   * @param derivedContexts derived contexts
   * @return none if some inputs were missing, validated JSON context if lookup performed
   */
  def lookup(
    event: EnrichedEvent,
    derivedContexts: List[SelfDescribingData[Json]],
    customContexts: List[SelfDescribingData[Json]],
    unstructEvent: Option[SelfDescribingData[Json]]
  ): F[ValidatedNel[FailureDetails.EnrichmentFailure, List[SelfDescribingData[Json]]]] = {
    val templateContext =
      Input.buildTemplateContext(
        inputs,
        event,
        derivedContexts,
        customContexts,
        unstructEvent
      )

    val contexts: EitherT[F, NonEmptyList[String], List[SelfDescribingData[Json]]] =
      for {
        context <- EitherT.fromEither[F](templateContext.toEither)
        jsons <- getOutputs(context)
        contexts = jsons.parTraverse { json =>
                     SelfDescribingData
                       .parse(json)
                       .leftMap(e => NonEmptyList.one(s"${json.noSpaces} is not self-describing JSON, ${e.code}"))
                   }
        outputs <- EitherT.fromEither[F](contexts)
      } yield outputs

    contexts.leftMap(failureDetails).toValidated.map {
      case Validated.Invalid(_) if ignoreOnError => Validated.Valid(List.empty)
      case other => other
    }
  }

  /**
   * Build URI and try to get value for each of [[outputs]]
   * @param validInputs map to build template context
   * @return validated list of lookups, whole lookup will be failed if any of outputs were failed
   */
  private[apirequest] def getOutputs(validInputs: Option[Map[String, String]]): EitherT[F, NonEmptyList[String], List[Json]] = {
    val result: List[F[Either[Throwable, Json]]] =
      for {
        templateContext <- validInputs.toList
        url <- api.buildUrl(templateContext).toList
        output <- outputs
        body = api.buildBody(templateContext)
      } yield cachedOrRequest(url, body, output)

    result.parTraverse { action =>
      EitherT(action.map { result =>
        result.leftMap(_.getMessage).toEitherNel
      })
    }
  }

  /**
   * Check cache for URL and perform HTTP request if value wasn't found
   * @param url URL to request
   * @param output currently processing output
   * @return validated JObject, in case of success ready to be attached to derived contexts
   */
  private[apirequest] def cachedOrRequest(
    url: String,
    body: Option[String],
    output: Output
  ): F[Either[Throwable, Json]] =
    for {
      result <- apiRequestEvaluator.evaluateForKey(key = cacheKey(url, body), getResult = () => callApi(url, body, output))
      extracted = result.flatMap(output.extract)
      described = extracted.map(output.describeJson)
    } yield described

  private def callApi(
    url: String,
    body: Option[String],
    output: Output
  ): F[Either[Throwable, Json]] =
    for {
      response <- api.perform[F](url, body)
      json = response.flatMap(output.parseResponse)
    } yield json

  private def failureDetails(errors: NonEmptyList[String]) =
    errors.map { error =>
      val message = FailureDetails.EnrichmentFailureMessage.Simple(error)
      FailureDetails.EnrichmentFailure(enrichmentInfo, message)
    }
}

sealed trait CreateApiRequestEnrichment[F[_]] {
  def create(conf: ApiRequestConf): F[ApiRequestEnrichment[F]]
}

object CreateApiRequestEnrichment {
  def apply[F[_]](implicit ev: CreateApiRequestEnrichment[F]): CreateApiRequestEnrichment[F] = ev

  implicit def instance[F[_]: Monad: HttpClient: Clock](
    implicit CLM: CreateLruMap[F, String, CachingEvaluator.CachedItem[Json]]
  ): CreateApiRequestEnrichment[F] =
    new CreateApiRequestEnrichment[F] {
      def create(conf: ApiRequestConf): F[ApiRequestEnrichment[F]] = {
        val cacheConfig = CachingEvaluator.Config(
          size = conf.cache.size,
          successTtl = conf.cache.ttl,
          errorTtl = conf.cache.ttl / 10
        )

        CachingEvaluator
          .create[F, String, Json](cacheConfig)
          .map { evaluator =>
            ApiRequestEnrichment(
              conf.schemaKey,
              conf.inputs,
              conf.api,
              conf.outputs,
              evaluator,
              conf.ignoreOnError
            )
          }
      }
    }
}
