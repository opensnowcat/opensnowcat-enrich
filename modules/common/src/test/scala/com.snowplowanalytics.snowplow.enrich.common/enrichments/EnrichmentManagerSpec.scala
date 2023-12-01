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

import cats.Id
import cats.implicits._
import cats.data.NonEmptyList
import io.circe.literal._
import io.circe.parser.{parse => jparse}
import org.joda.time.DateTime
import com.snowplowanalytics.snowplow.badrows._
import com.snowplowanalytics.iglu.core.{SchemaCriterion, SchemaKey, SchemaVer}
import loaders._
import adapters.RawEvent
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.pii.{
  JsonMutators,
  PiiJson,
  PiiPseudonymizerEnrichment,
  PiiStrategyPseudonymize
}
import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import utils.Clock._
import utils.ConversionUtils
import enrichments.registry.{HttpHeaderExtractorEnrichment, IabEnrichment, JavascriptScriptEnrichment, YauaaEnrichment}
import org.apache.commons.codec.digest.DigestUtils
import org.specs2.mutable.Specification
import org.specs2.matcher.EitherMatchers
import SpecHelpers._
import com.snowplowanalytics.snowplow.badrows.FailureDetails.EnrichmentFailureMessage

class EnrichmentManagerSpec extends Specification with EitherMatchers {
  import EnrichmentManagerSpec._

  "enrichEvent" should {
    "return a SchemaViolations bad row if the input event contains an invalid context" >> {
      val parameters = Map(
        "e" -> "pp",
        "tv" -> "js-0.13.1",
        "p" -> "web",
        "co" -> """
          {
            "schema": "iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-0",
            "data": [
              {
                "schema":"iglu:com.acme/email_sent/jsonschema/1-0-0",
                "data": {
                  "foo": "hello@world.com",
                  "emailAddress2": "foo@bar.org"
                }
              }
            ]
          }
        """
      ).toOpt
      val rawEvent = RawEvent(api, parameters, None, source, context)
      val enriched = EnrichmentManager.enrichEvent[Id](
        enrichmentReg,
        client,
        processor,
        timestamp,
        rawEvent,
        AcceptInvalid.featureFlags,
        AcceptInvalid.countInvalid
      )

      enriched.value must beLeft.like {
        case _: BadRow.SchemaViolations => ok
        case br => ko(s"bad row [$br] is not SchemaViolations")
      }
    }

    "return a SchemaViolations bad row if the input unstructured event is invalid" >> {
      val parameters = Map(
        "e" -> "ue",
        "tv" -> "js-0.13.1",
        "p" -> "web",
        "ue_pr" -> """
          {
            "schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
            "data":{
              "schema":"iglu:com.acme/email_sent/jsonschema/1-0-0",
              "data": {
                "emailAddress": "hello@world.com",
                "emailAddress2": "foo@bar.org",
                "unallowedAdditionalField": "foo@bar.org"
              }
            }
          }"""
      ).toOpt
      val rawEvent = RawEvent(api, parameters, None, source, context)
      val enriched = EnrichmentManager.enrichEvent[Id](
        enrichmentReg,
        client,
        processor,
        timestamp,
        rawEvent,
        AcceptInvalid.featureFlags,
        AcceptInvalid.countInvalid
      )
      enriched.value must beLeft.like {
        case _: BadRow.SchemaViolations => ok
        case br => ko(s"bad row [$br] is not SchemaViolations")
      }
    }

    "return an EnrichmentFailures bad row if one of the enrichment (JS enrichment here) fails" >> {
      val script = """
        function process(event) {
          throw "Javascript exception";
          return [ { a: "b" } ];
        }"""

      val config = json"""{
        "parameters": {
          "script": ${ConversionUtils.encodeBase64Url(script)}
        }
      }"""
      val schemaKey = SchemaKey(
        "com.snowplowanalytics.snowplow",
        "javascript_script_config",
        "jsonschema",
        SchemaVer.Full(1, 0, 0)
      )
      val jsEnrichConf =
        JavascriptScriptEnrichment.parse(config, schemaKey).toOption.get
      val jsEnrich = JavascriptScriptEnrichment(jsEnrichConf.schemaKey, jsEnrichConf.rawFunction)
      val enrichmentReg = EnrichmentRegistry[Id](javascriptScript = Some(jsEnrich))

      val parameters = Map(
        "e" -> "pp",
        "tv" -> "js-0.13.1",
        "p" -> "web"
      ).toOpt
      val rawEvent = RawEvent(api, parameters, None, source, context)
      val enriched = EnrichmentManager.enrichEvent[Id](
        enrichmentReg,
        client,
        processor,
        timestamp,
        rawEvent,
        AcceptInvalid.featureFlags,
        AcceptInvalid.countInvalid
      )
      enriched.value must beLeft.like {
        case BadRow.EnrichmentFailures(
              _,
              Failure.EnrichmentFailures(
                _,
                NonEmptyList(
                  FailureDetails.EnrichmentFailure(
                    _,
                    _: FailureDetails.EnrichmentFailureMessage.Simple
                  ),
                  Nil
                )
              ),
              _
            ) =>
          ok
        case br =>
          ko(
            s"bad row [$br] is not an EnrichmentFailures containing one EnrichmentFailureMessage.Simple"
          )
      }
    }

    "return an EnrichmentFailures bad row containing one IgluError if one of the contexts added by the enrichments is invalid" >> {
      val script = """
        function process(event) {
          return [ { schema: "iglu:com.acme/email_sent/jsonschema/1-0-0",
                     data: {
                       emailAddress: "hello@world.com",
                       foo: "bar"
                     }
                   } ];
        }"""

      val config = json"""{
        "parameters": {
          "script": ${ConversionUtils.encodeBase64Url(script)}
        }
      }"""
      val schemaKey = SchemaKey(
        "com.snowplowanalytics.snowplow",
        "javascript_script_config",
        "jsonschema",
        SchemaVer.Full(1, 0, 0)
      )
      val jsEnrichConf =
        JavascriptScriptEnrichment.parse(config, schemaKey).toOption.get
      val jsEnrich = JavascriptScriptEnrichment(jsEnrichConf.schemaKey, jsEnrichConf.rawFunction)
      val enrichmentReg = EnrichmentRegistry[Id](javascriptScript = Some(jsEnrich))

      val parameters = Map(
        "e" -> "pp",
        "tv" -> "js-0.13.1",
        "p" -> "web"
      ).toOpt
      val rawEvent = RawEvent(api, parameters, None, source, context)
      val enriched = EnrichmentManager.enrichEvent[Id](
        enrichmentReg,
        client,
        processor,
        timestamp,
        rawEvent,
        AcceptInvalid.featureFlags,
        AcceptInvalid.countInvalid
      )
      enriched.value must beLeft.like {
        case BadRow.EnrichmentFailures(
              _,
              Failure.EnrichmentFailures(
                _,
                NonEmptyList(
                  FailureDetails.EnrichmentFailure(
                    _,
                    _: FailureDetails.EnrichmentFailureMessage.IgluError
                  ),
                  Nil
                )
              ),
              payload
            ) if payload.enriched.derived_contexts.isDefined =>
          ok
        case br => ko(s"bad row [$br] is not an EnrichmentFailures containing one IgluError and with derived_contexts defined")
      }
    }

    "emit an EnrichedEvent if everything goes well" >> {
      val parameters = Map(
        "e" -> "ue",
        "tv" -> "js-0.13.1",
        "p" -> "web",
        "co" -> """
          {
            "schema": "iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-0",
            "data": [
              {
                "schema":"iglu:com.acme/email_sent/jsonschema/1-0-0",
                "data": {
                  "emailAddress": "hello@world.com",
                  "emailAddress2": "foo@bar.org"
                }
              }
            ]
          }
        """,
        "ue_pr" -> """
          {
            "schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
            "data":{
              "schema":"iglu:com.acme/email_sent/jsonschema/1-0-0",
              "data": {
                "emailAddress": "hello@world.com",
                "emailAddress2": "foo@bar.org"
              }
            }
          }"""
      ).toOpt
      val rawEvent = RawEvent(api, parameters, None, source, context)
      val enriched = EnrichmentManager.enrichEvent[Id](
        enrichmentReg,
        client,
        processor,
        timestamp,
        rawEvent,
        AcceptInvalid.featureFlags,
        AcceptInvalid.countInvalid
      )
      enriched.value must beRight
    }

    "emit an EnrichedEvent if a PII value that needs to be hashed is an empty string" >> {
      val parameters = Map(
        "e" -> "ue",
        "tv" -> "js-0.13.1",
        "p" -> "web",
        "co" -> """
          {
            "schema": "iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-0",
            "data": [
              {
                "schema":"iglu:com.acme/email_sent/jsonschema/1-0-0",
                "data": {
                  "emailAddress": "hello@world.com",
                  "emailAddress2": "foo@bar.org"
                }
              }
            ]
          }
        """,
        "ue_pr" -> """
          {
            "schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
            "data":{
              "schema":"iglu:com.acme/email_sent/jsonschema/1-0-0",
              "data": {
                "emailAddress": "hello@world.com",
                "emailAddress2": "foo@bar.org",
                "emailAddress3": ""
              }
            }
          }"""
      ).toOpt
      val rawEvent = RawEvent(api, parameters, None, source, context)
      val enrichmentReg = EnrichmentRegistry[Id](
        piiPseudonymizer = PiiPseudonymizerEnrichment(
          List(
            PiiJson(
              fieldMutator = JsonMutators("unstruct_event"),
              schemaCriterion = SchemaCriterion("com.acme", "email_sent", "jsonschema", 1, 0, 0),
              jsonPath = "$.emailAddress3"
            )
          ),
          false,
          PiiStrategyPseudonymize(
            "MD5",
            hashFunction = DigestUtils.sha256Hex(_: Array[Byte]),
            "pepper123"
          )
        ).some
      )
      val enriched = EnrichmentManager.enrichEvent[Id](
        enrichmentReg,
        client,
        processor,
        timestamp,
        rawEvent,
        AcceptInvalid.featureFlags,
        AcceptInvalid.countInvalid
      )
      enriched.value must beRight
    }

    "emit an EnrichedEvent if a PII value that needs to be hashed is null" >> {
      val parameters = Map(
        "e" -> "ue",
        "tv" -> "js-0.13.1",
        "p" -> "web",
        "co" -> """
          {
            "schema": "iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-0",
            "data": [
              {
                "schema":"iglu:com.acme/email_sent/jsonschema/1-0-0",
                "data": {
                  "emailAddress": "hello@world.com",
                  "emailAddress2": "foo@bar.org"
                }
              }
            ]
          }
        """,
        "ue_pr" -> """
          {
            "schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
            "data":{
              "schema":"iglu:com.acme/email_sent/jsonschema/2-0-0",
              "data": {
                "emailAddress": "hello@world.com",
                "emailAddress2": "foo@bar.org",
                "emailAddress3": null
              }
            }
          }"""
      ).toOpt
      val rawEvent = RawEvent(api, parameters, None, source, context)
      val enrichmentReg = EnrichmentRegistry[Id](
        piiPseudonymizer = PiiPseudonymizerEnrichment(
          List(
            PiiJson(
              fieldMutator = JsonMutators("unstruct_event"),
              schemaCriterion = SchemaCriterion("com.acme", "email_sent", "jsonschema", 1, 0, 0),
              jsonPath = "$.emailAddress3"
            )
          ),
          false,
          PiiStrategyPseudonymize(
            "MD5",
            hashFunction = DigestUtils.sha256Hex(_: Array[Byte]),
            "pepper123"
          )
        ).some
      )
      val enriched = EnrichmentManager.enrichEvent[Id](
        enrichmentReg,
        client,
        processor,
        timestamp,
        rawEvent,
        AcceptInvalid.featureFlags,
        AcceptInvalid.countInvalid
      )
      enriched.value must beRight
    }

    "fail to emit an EnrichedEvent if a PII value that needs to be hashed is an empty object" >> {
      val parameters = Map(
        "e" -> "ue",
        "tv" -> "js-0.13.1",
        "p" -> "web",
        "co" -> """
          {
            "schema": "iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-0",
            "data": [
              {
                "schema":"iglu:com.acme/email_sent/jsonschema/1-0-0",
                "data": {
                  "emailAddress": "hello@world.com",
                  "emailAddress2": "foo@bar.org"
                }
              }
            ]
          }
        """,
        "ue_pr" -> """
          {
            "schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
            "data":{
              "schema":"iglu:com.acme/email_sent/jsonschema/1-0-0",
              "data": {
                "emailAddress": "hello@world.com",
                "emailAddress2": "foo@bar.org",
                "emailAddress3": {}
              }
            }
          }"""
      ).toOpt
      val rawEvent = RawEvent(api, parameters, None, source, context)
      val enrichmentReg = EnrichmentRegistry[Id](
        piiPseudonymizer = PiiPseudonymizerEnrichment(
          List(
            PiiJson(
              fieldMutator = JsonMutators("unstruct_event"),
              schemaCriterion = SchemaCriterion("com.acme", "email_sent", "jsonschema", 1, 0, 0),
              jsonPath = "$.emailAddress3"
            )
          ),
          false,
          PiiStrategyPseudonymize(
            "MD5",
            hashFunction = DigestUtils.sha256Hex(_: Array[Byte]),
            "pepper123"
          )
        ).some
      )
      val enriched = EnrichmentManager.enrichEvent[Id](
        enrichmentReg,
        client,
        processor,
        timestamp,
        rawEvent,
        AcceptInvalid.featureFlags,
        AcceptInvalid.countInvalid
      )
      enriched.value must beLeft
    }

    "fail to emit an EnrichedEvent if a context PII value that needs to be hashed is an empty object" >> {
      val parameters = Map(
        "e" -> "ue",
        "tv" -> "js-0.13.1",
        "p" -> "web",
        "co" -> """
          {
            "schema": "iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-0",
            "data": [
              {
                "schema":"iglu:com.acme/email_sent/jsonschema/1-0-0",
                "data": {
                  "emailAddress": "hello@world.com",
                  "emailAddress2": "foo@bar.org",
                  "emailAddress3": {}
                }
              }
            ]
          }
        """,
        "ue_pr" -> """
          {
            "schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
            "data":{
              "schema":"iglu:com.acme/email_sent/jsonschema/1-0-0",
              "data": {
                "emailAddress": "hello@world.com",
                "emailAddress2": "foo@bar.org"
              }
            }
          }"""
      ).toOpt
      val rawEvent = RawEvent(api, parameters, None, source, context)
      val enrichmentReg = EnrichmentRegistry[Id](
        piiPseudonymizer = PiiPseudonymizerEnrichment(
          List(
            PiiJson(
              fieldMutator = JsonMutators("contexts"),
              schemaCriterion = SchemaCriterion("com.acme", "email_sent", "jsonschema", 1, 0, 0),
              jsonPath = "$.emailAddress3"
            )
          ),
          false,
          PiiStrategyPseudonymize(
            "MD5",
            hashFunction = DigestUtils.sha256Hex(_: Array[Byte]),
            "pepper123"
          )
        ).some
      )
      val enriched = EnrichmentManager.enrichEvent[Id](
        enrichmentReg,
        client,
        processor,
        timestamp,
        rawEvent,
        AcceptInvalid.featureFlags,
        AcceptInvalid.countInvalid
      )
      enriched.value must beLeft
    }

    "fail to emit an EnrichedEvent if a PII value needs to be hashed in both co and ue and is invalid in one of them" >> {
      val parameters = Map(
        "e" -> "ue",
        "tv" -> "js-0.13.1",
        "p" -> "web",
        "co" -> """
          {
            "schema": "iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-0",
            "data": [
              {
                "schema":"iglu:com.acme/email_sent/jsonschema/1-0-0",
                "data": {
                  "emailAddress": "hello@world.com",
                  "emailAddress2": "foo@bar.org",
                  "emailAddress3": {}
                }
              }
            ]
          }
        """,
        "ue_pr" -> """
          {
            "schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
            "data":{
              "schema":"iglu:com.acme/email_sent/jsonschema/1-0-0",
              "data": {
                "emailAddress": "hello@world.com",
                "emailAddress2": "foo@bar.org",
                "emailAddress3": ""
              }
            }
          }"""
      ).toOpt
      val rawEvent = RawEvent(api, parameters, None, source, context)
      val enrichmentReg = EnrichmentRegistry[Id](
        piiPseudonymizer = PiiPseudonymizerEnrichment(
          List(
            PiiJson(
              fieldMutator = JsonMutators("contexts"),
              schemaCriterion = SchemaCriterion("com.acme", "email_sent", "jsonschema", 1, 0, 0),
              jsonPath = "$.emailAddress3"
            ),
            PiiJson(
              fieldMutator = JsonMutators("unstruct_event"),
              schemaCriterion = SchemaCriterion("com.acme", "email_sent", "jsonschema", 1, 0, 0),
              jsonPath = "$.emailAddress3"
            )
          ),
          false,
          PiiStrategyPseudonymize(
            "MD5",
            hashFunction = DigestUtils.sha256Hex(_: Array[Byte]),
            "pepper123"
          )
        ).some
      )
      val enriched = EnrichmentManager.enrichEvent[Id](
        enrichmentReg,
        client,
        processor,
        timestamp,
        rawEvent,
        AcceptInvalid.featureFlags,
        AcceptInvalid.countInvalid
      )
      enriched.value must beLeft
    }

    "emit an EnrichedEvent for valid integer fields" >> {
      val integers = List("42", "-42", "null")
      val fields = List("tid", "vid", "ti_qu", "pp_mix", "pp_max", "pp_miy", "pp_may")

      integers
        .flatMap { integer =>
          fields.map { field =>
            val parameters = Map(
              "e" -> "ue",
              "tv" -> "js-0.13.1",
              "p" -> "web",
              field -> integer
            ).toOpt
            val rawEvent = RawEvent(api, parameters, None, source, context)
            val enriched = EnrichmentManager.enrichEvent[Id](
              enrichmentReg,
              client,
              processor,
              timestamp,
              rawEvent,
              AcceptInvalid.featureFlags,
              AcceptInvalid.countInvalid
            )
            enriched.value must beRight
          }
        }
        .reduce(_ and _)
    }

    "emit an EnrichedEvent for valid decimal fields" >> {
      val decimals = List("42", "42.5", "null")
      val fields = List("ev_va", "se_va", "tr_tt", "tr_tx", "tr_sh", "ti_pr")

      decimals
        .flatMap { decimal =>
          fields.map { field =>
            val parameters = Map(
              "e" -> "ue",
              "tv" -> "js-0.13.1",
              "p" -> "web",
              field -> decimal
            ).toOpt
            val rawEvent = RawEvent(api, parameters, None, source, context)
            val enriched = EnrichmentManager.enrichEvent[Id](
              enrichmentReg,
              client,
              processor,
              timestamp,
              rawEvent,
              AcceptInvalid.featureFlags,
              AcceptInvalid.countInvalid
            )
            enriched.value must beRight
          }
        }
        .reduce(_ and _)
    }

    "create an EnrichedEvent with correct BigDecimal field values" >> {
      val decimals = List(
        // input, expected
        ("42", "42"),
        ("42.5", "42.5"),
        ("137777104559", "137777104559"),
        ("-137777104559", "-137777104559"),
        ("1E7", "10000000"),
        ("1.2E9", "1200000000"),
        ("0.000001", "0.000001"),
        ("0.0000001", "1E-7") // unavoidable consequence, due to BigDecimal internal representation
      )

      decimals
        .map {
          case (input, expected) =>
            val parameters = Map(
              "e" -> "ue",
              "tv" -> "js-0.13.1",
              "p" -> "web",
              "ev_va" -> input
            ).toOpt
            val rawEvent = RawEvent(api, parameters, None, source, context)
            val enriched = EnrichmentManager.enrichEvent[Id](
              enrichmentReg,
              client,
              processor,
              timestamp,
              rawEvent,
              AcceptInvalid.featureFlags,
              AcceptInvalid.countInvalid
            )
            enriched.value must beRight { ee: EnrichedEvent =>
              ee.se_value.toString must_== expected
            }
        }
        .reduce(_ and _)
    }

    "have a preference of 'ua' query string parameter over user agent of HTTP header" >> {
      val qs_ua = "Mozilla/5.0 (X11; Linux x86_64; rv:75.0) Gecko/20100101 Firefox/75.0"
      val parameters = Map(
        "e" -> "pp",
        "tv" -> "js-0.13.1",
        "ua" -> qs_ua,
        "p" -> "web"
      ).toOpt
      val contextWithUa = context.copy(useragent = Some("header-useragent"))
      val rawEvent = RawEvent(api, parameters, None, source, contextWithUa)
      val enriched = EnrichmentManager.enrichEvent[Id](
        enrichmentReg,
        client,
        processor,
        timestamp,
        rawEvent,
        AcceptInvalid.featureFlags,
        AcceptInvalid.countInvalid
      )
      enriched.value.map(_.useragent) must beRight(qs_ua)
      enriched.value.map(_.derived_contexts) must beRight((_: String).contains("\"agentName\":\"Firefox\""))
    }

    "use user agent of HTTP header if 'ua' query string parameter is not set" >> {
      val parameters = Map(
        "e" -> "pp",
        "tv" -> "js-0.13.1",
        "p" -> "web"
      ).toOpt
      val contextWithUa = context.copy(useragent = Some("header-useragent"))
      val rawEvent = RawEvent(api, parameters, None, source, contextWithUa)
      val enriched = EnrichmentManager.enrichEvent[Id](
        enrichmentReg,
        client,
        processor,
        timestamp,
        rawEvent,
        AcceptInvalid.featureFlags,
        AcceptInvalid.countInvalid
      )
      enriched.value.map(_.useragent) must beRight("header-useragent")
    }

    "accept user agent of HTTP header when it is not URL decodable" >> {
      val parameters = Map(
        "e" -> "pp",
        "tv" -> "js-0.13.1",
        "p" -> "web"
      ).toOpt
      val ua = "Mozilla/5.0 (X11; Linux x86_64; rv:75.0) Gecko/20100101 %1$s/%2$s Firefox/75.0"
      val contextWithUa = context.copy(useragent = Some(ua))
      val rawEvent = RawEvent(api, parameters, None, source, contextWithUa)
      val enriched = EnrichmentManager.enrichEvent[Id](
        enrichmentReg,
        client,
        processor,
        timestamp,
        rawEvent,
        AcceptInvalid.featureFlags,
        AcceptInvalid.countInvalid
      )
      enriched.value.map(_.useragent) must beRight(ua)
    }

    "accept 'ua' in query string when it is not URL decodable" >> {
      val qs_ua = "Mozilla/5.0 (X11; Linux x86_64; rv:75.0) Gecko/20100101 %1$s/%2$s Firefox/75.0"
      val parameters = Map(
        "e" -> "pp",
        "tv" -> "js-0.13.1",
        "ua" -> qs_ua,
        "p" -> "web"
      ).toOpt
      val contextWithUa = context.copy(useragent = Some("header-useragent"))
      val rawEvent = RawEvent(api, parameters, None, source, contextWithUa)
      val enriched = EnrichmentManager.enrichEvent[Id](
        enrichmentReg,
        client,
        processor,
        timestamp,
        rawEvent,
        AcceptInvalid.featureFlags,
        AcceptInvalid.countInvalid
      )
      enriched.value.map(_.useragent) must beRight(qs_ua)
      enriched.value.map(_.derived_contexts) must beRight((_: String).contains("\"agentName\":\"%1$S\""))
    }

    "pass derived contexts generated by previous enrichments to the JavaScript enrichment" >> {
      val script =
        """
        function process(event) {
          var derivedContexts = JSON.parse(event.getDerived_contexts());
          var firstHeaderValue = derivedContexts.data[0].data.value;
          event.setApp_id(firstHeaderValue);
          return [];
        }"""

      val schemaKey = SchemaKey(
        "com.snowplowanalytics.snowplow",
        "javascript_script_config",
        "jsonschema",
        SchemaVer.Full(1, 0, 0)
      )
      val enrichmentReg = EnrichmentRegistry[Id](
        javascriptScript = Some(JavascriptScriptEnrichment(schemaKey, script)),
        httpHeaderExtractor = Some(HttpHeaderExtractorEnrichment(".*"))
      )

      val parameters = Map(
        "e" -> "pp",
        "tv" -> "js-0.13.1",
        "p" -> "web"
      ).toOpt
      val headerContext = context.copy(headers = List("X-Tract-Me: moo"))
      val rawEvent = RawEvent(api, parameters, None, source, headerContext)
      val enriched = EnrichmentManager.enrichEvent[Id](
        enrichmentReg,
        client,
        processor,
        timestamp,
        rawEvent,
        AcceptInvalid.featureFlags,
        AcceptInvalid.countInvalid
      )
      enriched.value.map(_.app_id) must beRight("moo")
    }

    "emit an EnrichedEvent with superseded schemas" >> {
      val expectedContexts = jparse(
        """
        {
          "schema": "iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1",
          "data": [
            {
              "schema":"iglu:com.acme/email_sent/jsonschema/1-0-0",
              "data": {
                "emailAddress": "hello@world.com",
                "emailAddress2": "foo@bar.org"
              }
            },
            {
              "schema":"iglu:com.acme/superseding_example/jsonschema/1-0-1",
              "data": {
                "field_a": "value_a",
                "field_b": "value_b"
              }
            },
            {
              "schema":"iglu:com.acme/superseding_example/jsonschema/1-0-1",
              "data": {
                "field_a": "value_a",
                "field_b": "value_b",
                "field_d": "value_d"
              }
            },
            {
              "schema":"iglu:com.acme/superseding_example/jsonschema/1-0-1",
              "data": {
                "field_a": "value_a",
                "field_b": "value_b",
                "field_c": "value_c",
                "field_d": "value_d"
              }
            }
          ]
        }
        """
      ).toOption.get
      val expectedDerivedContexts = jparse(
        """
        {
          "schema": "iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1",
          "data": [
            {
              "schema":"iglu:com.snowplowanalytics.iglu/validation_info/jsonschema/1-0-0",
              "data":{
                "originalSchema":"iglu:com.acme/superseding_example/jsonschema/1-0-0",
                "validatedWith":"1-0-1"
              }
            },
            {
              "schema":"iglu:com.snowplowanalytics.iglu/validation_info/jsonschema/1-0-0",
              "data":{
                "originalSchema":"iglu:com.acme/superseding_example/jsonschema/2-0-0",
                "validatedWith":"2-0-1"
              }
            }
          ]
        }
        """
      ).toOption.get
      val expectedUnstructEvent = jparse(
        """
        {
          "schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
          "data":{
            "schema":"iglu:com.acme/superseding_example/jsonschema/2-0-1",
            "data": {
              "field_e": "value_e",
              "field_f": "value_f",
              "field_g": "value_g"
            }
          }
        }
        """
      ).toOption.get
      val parameters = Map(
        "e" -> "ue",
        "tv" -> "js-0.13.1",
        "p" -> "web",
        "co" ->
          """
          {
            "schema": "iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-0",
            "data": [
              {
                "schema":"iglu:com.acme/email_sent/jsonschema/1-0-0",
                "data": {
                  "emailAddress": "hello@world.com",
                  "emailAddress2": "foo@bar.org"
                }
              },
              {
                "schema":"iglu:com.acme/superseding_example/jsonschema/1-0-0",
                "data": {
                  "field_a": "value_a",
                  "field_b": "value_b"
                }
              },
              {
                "schema":"iglu:com.acme/superseding_example/jsonschema/1-0-0",
                "data": {
                  "field_a": "value_a",
                  "field_b": "value_b",
                  "field_d": "value_d"
                }
              },
              {
                "schema":"iglu:com.acme/superseding_example/jsonschema/1-0-1",
                "data": {
                  "field_a": "value_a",
                  "field_b": "value_b",
                  "field_c": "value_c",
                  "field_d": "value_d"
                }
              }
            ]
          }
        """,
        "ue_pr" ->
          """
          {
            "schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
            "data":{
              "schema":"iglu:com.acme/superseding_example/jsonschema/2-0-0",
              "data": {
                "field_e": "value_e",
                "field_f": "value_f",
                "field_g": "value_g"
              }
            }
          }"""
      ).toOpt
      val rawEvent = RawEvent(api, parameters, None, source, context)
      val enriched = EnrichmentManager.enrichEvent[Id](
        enrichmentReg.copy(yauaa = None),
        client,
        processor,
        timestamp,
        rawEvent,
        AcceptInvalid.featureFlags,
        AcceptInvalid.countInvalid
      )

      enriched.value must beRight.like {
        case e: EnrichedEvent =>
          val p = EnrichedEvent.toPartiallyEnrichedEvent(e)
          val contextsJson = jparse(p.contexts.get).toOption.get
          val derivedContextsJson = jparse(p.derived_contexts.get).toOption.get
          val ueJson = jparse(p.unstruct_event.get).toOption.get
          (contextsJson must beEqualTo(expectedContexts)) and
            (derivedContextsJson must beEqualTo(expectedDerivedContexts)) and
            (ueJson must beEqualTo(expectedUnstructEvent))
        case _ => ko
      }
    }
  }

  "getIabContext" should {
    "return no context if useragent is null" >> {
      val input = new EnrichedEvent()
      input.setUser_ipaddress("127.0.0.1")
      input.setDerived_tstamp("2010-06-30 01:20:01.000")
      val inputState = EnrichmentManager.Accumulation(input, Nil, Nil)
      EnrichmentManager.getIabContext[Id](iabEnrichment).runS(inputState) must beLike {
        case acc: EnrichmentManager.Accumulation =>
          (acc.errors must beEmpty) and (acc.contexts must beEmpty)
      }
    }

    "return no context if user_ipaddress is null" >> {
      val input = new EnrichedEvent()
      input.setUseragent("Firefox")
      input.setDerived_tstamp("2010-06-30 01:20:01.000")
      val inputState = EnrichmentManager.Accumulation(input, Nil, Nil)
      EnrichmentManager.getIabContext[Id](iabEnrichment).runS(inputState) must beLike {
        case acc: EnrichmentManager.Accumulation =>
          (acc.errors must beEmpty) and (acc.contexts must beEmpty)
      }
    }

    "return no context if derived_tstamp is null" >> {
      val input = new EnrichedEvent()
      input.setUser_ipaddress("127.0.0.1")
      input.setUseragent("Firefox")
      val inputState = EnrichmentManager.Accumulation(input, Nil, Nil)
      EnrichmentManager.getIabContext[Id](iabEnrichment).runS(inputState) must beLike {
        case acc: EnrichmentManager.Accumulation =>
          (acc.errors must beEmpty) and (acc.contexts must beEmpty)
      }
    }

    "return no context if user_ipaddress is invalid" >> {
      val input = new EnrichedEvent()
      input.setUser_ipaddress("invalid")
      input.setUseragent("Firefox")
      input.setDerived_tstamp("2010-06-30 01:20:01.000")
      val inputState = EnrichmentManager.Accumulation(input, Nil, Nil)
      EnrichmentManager.getIabContext[Id](iabEnrichment).runS(inputState) must beLike {
        case acc: EnrichmentManager.Accumulation =>
          (acc.errors must beEmpty) and (acc.contexts must beEmpty)
      }
    }

    "return no context if user_ipaddress is hostname (don't try to resovle it)" >> {
      val input = new EnrichedEvent()
      input.setUser_ipaddress("localhost")
      input.setUseragent("Firefox")
      input.setDerived_tstamp("2010-06-30 01:20:01.000")
      val inputState = EnrichmentManager.Accumulation(input, Nil, Nil)
      EnrichmentManager.getIabContext[Id](iabEnrichment).runS(inputState) must beLike {
        case acc: EnrichmentManager.Accumulation =>
          (acc.errors must beEmpty) and (acc.contexts must beEmpty)
      }
    }

    "return Some if all arguments are valid" >> {
      val input = new EnrichedEvent()
      input.setUser_ipaddress("127.0.0.1")
      input.setUseragent("Firefox")
      input.setDerived_tstamp("2010-06-30 01:20:01.000")
      val inputState = EnrichmentManager.Accumulation(input, Nil, Nil)
      EnrichmentManager.getIabContext[Id](iabEnrichment).runS(inputState) must beLike {
        case acc: EnrichmentManager.Accumulation =>
          (acc.errors must beEmpty) and (acc.contexts must not beEmpty)
      }
    }
  }

  "getCollectorVersionSet" should {
    "return an enrichment failure if v_collector is null or empty" >> {
      val input = new EnrichedEvent()
      val inputState = EnrichmentManager.Accumulation(input, Nil, Nil)
      EnrichmentManager.getCollectorVersionSet[Id].runS(inputState) must beLike {
        case acc: EnrichmentManager.Accumulation =>
          acc.errors must not beEmpty
      }
      input.v_collector = ""
      EnrichmentManager.getCollectorVersionSet[Id].runS(inputState) must beLike {
        case acc: EnrichmentManager.Accumulation =>
          acc.errors must not beEmpty
      }
    }

    "return Unit if v_collector is set" >> {
      val input = new EnrichedEvent()
      input.v_collector = "v42"
      val inputState = EnrichmentManager.Accumulation(input, Nil, Nil)
      EnrichmentManager.getCollectorVersionSet[Id].runS(inputState) must beLike {
        case acc: EnrichmentManager.Accumulation =>
          acc.errors must beEmpty
      }
    }
  }

  "validateEnriched" should {
    "create a bad row if a field is oversized" >> {
      val result = EnrichmentManager
        .enrichEvent[Id](
          enrichmentReg,
          client,
          processor,
          timestamp,
          RawEvent(api, fatBody, None, source, context),
          featureFlags = AcceptInvalid.featureFlags.copy(acceptInvalid = false),
          AcceptInvalid.countInvalid
        )
        .value

      result must beLeft.like {
        case badRow: BadRow.EnrichmentFailures =>
          val firstError = badRow.failure.messages.head.message
          val secondError = badRow.failure.messages.last.message

          firstError must beEqualTo(
            EnrichmentFailureMessage.Simple("Enriched event does not conform to atomic schema field's length restrictions")
          )
          secondError must beEqualTo(EnrichmentFailureMessage.Simple("Field v_tracker longer than maximum allowed size 100"))
        case br =>
          ko(s"bad row [$br] is not BadRow.EnrichmentFailures")
      }
    }

    "not create a bad row if a field is oversized and acceptInvalid is set to true" >> {
      val result = EnrichmentManager
        .enrichEvent[Id](
          enrichmentReg,
          client,
          processor,
          timestamp,
          RawEvent(api, fatBody, None, source, context),
          featureFlags = AcceptInvalid.featureFlags.copy(acceptInvalid = true),
          AcceptInvalid.countInvalid
        )
        .value

      result must beRight[EnrichedEvent]
    }
  }
}

object EnrichmentManagerSpec {

  val enrichmentReg = EnrichmentRegistry[Id](yauaa = Some(YauaaEnrichment(None)))
  val client = SpecHelpers.client
  val processor = Processor("ssc-tests", "0.0.0")
  val timestamp = DateTime.now()

  val api = CollectorPayload.Api("com.snowplowanalytics.snowplow", "tp2")
  val source = CollectorPayload.Source("clj-tomcat", "UTF-8", None)
  val context = CollectorPayload.Context(
    DateTime.parse("2013-08-29T00:18:48.000+00:00").some,
    "37.157.33.123".some,
    None,
    None,
    Nil,
    None
  )

  val leanBody = Map(
    "e" -> "pp",
    "tv" -> "js-0.13.1",
    "p" -> "web"
  ).toOpt

  val fatBody = Map(
    "e" -> "pp",
    "tv" -> s"${"s" * 500}",
    "p" -> "web"
  ).toOpt

  val iabEnrichment = IabEnrichment
    .parse(
      json"""{
      "name": "iab_spiders_and_robots_enrichment",
      "vendor": "com.snowplowanalytics.snowplow.enrichments",
      "enabled": false,
      "parameters": {
        "ipFile": {
          "database": "ip_exclude_current_cidr.txt",
          "uri": "s3://my-private-bucket/iab"
        },
        "excludeUseragentFile": {
          "database": "exclude_current.txt",
          "uri": "s3://my-private-bucket/iab"
        },
        "includeUseragentFile": {
          "database": "include_current.txt",
          "uri": "s3://my-private-bucket/iab"
        }
      }
    }""",
      SchemaKey(
        "com.snowplowanalytics.snowplow.enrichments",
        "iab_spiders_and_robots_enrichment",
        "jsonschema",
        SchemaVer.Full(1, 0, 0)
      ),
      true
    )
    .toOption
    .getOrElse(throw new RuntimeException("IAB enrichment couldn't be initialised")) // to make sure it's not none
    .enrichment[Id]
    .some

}
