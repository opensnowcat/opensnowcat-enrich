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

import cats.Id
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.implicits._

import com.snowplowanalytics.forex.CreateForex._
import com.snowplowanalytics.forex.model._

import com.snowplowanalytics.iglu.core.{SchemaKey, SchemaVer}

import com.snowplowanalytics.snowplow.badrows.FailureDetails
import com.snowplowanalytics.snowplow.enrich.common.enrichments.registry.EnrichmentConf.CurrencyConversionConf

import org.joda.money.CurrencyUnit
import org.joda.time.DateTime

import java.math.BigDecimal

import org.specs2.Specification

class CurrencyConversionEnrichmentSpec extends Specification {
  import CurrencyConversionEnrichmentSpec._

  def is =
    skipAllIf(sys.env.get(OerApiKey).isEmpty) ^
      s2"""
  Failure for invalid transaction currency              $e1
  Failure for invalid transaction item currency         $e2
  Failure for invalid OER API key                       $e3
  Success for all fields absent                         $e4
  Success for all fields absent except currency         $e5
  Success for no transaction currency, tax, or shipping $e6
  Success for no transaction currency or total          $e7
  Success for no transaction currency                   $e8
  Success for transaction item null                     $e9
  Success for valid app id and API key                  $e10
  Success for both currencies null                      $e11
  Success for converting to the same currency           $e12
  Success for valid app id and API key                  $e13
  """

  def e1 = {
    val input =
      Input(
        Some("RUP"),
        Some(11.00),
        Some(1.17),
        Some(0.00),
        None,
        Some(17.99),
        Some(coTstamp)
      )
    val expected: Result = Validated.Invalid(
      NonEmptyList.of(
        ef(
          FailureDetails.EnrichmentFailureMessage
            .InputData("tr_currency", Some("RUP"), "Unknown currency 'RUP'")
        ),
        ef(
          FailureDetails.EnrichmentFailureMessage
            .InputData("tr_currency", Some("RUP"), "Unknown currency 'RUP'")
        ),
        ef(
          FailureDetails.EnrichmentFailureMessage
            .InputData("tr_currency", Some("RUP"), "Unknown currency 'RUP'")
        )
      )
    )
    val actual = runEnrichment(input)
    actual must beEqualTo(expected)
  }

  def e2 = {
    val input =
      Input(
        None,
        Some(12.00),
        Some(0.7),
        Some(0.00),
        Some("HUL"),
        Some(1.99),
        Some(coTstamp)
      )
    val expected: Result = ef(
      FailureDetails.EnrichmentFailureMessage.InputData(
        "ti_currency",
        Some("HUL"),
        "Unknown currency 'HUL'"
      )
    ).invalidNel
    val actual = runEnrichment(input)
    actual must beEqualTo(expected)
  }

  def e3 = {
    val input =
      Input(
        None,
        Some(13.00),
        Some(3.67),
        Some(0.00),
        Some("GBP"),
        Some(2.99),
        Some(coTstamp)
      )
    val wrongKey = "8A8A8A8A8A8A8A8A8A8A8A8AA8A8A8A8"
    val expected: Result = ef(
      FailureDetails.EnrichmentFailureMessage.Simple(
        "Open Exchange Rates error, type: [OtherErrors], message: [invalid_app_id]"
      )
    ).invalidNel
    val actual = runEnrichment(input, wrongKey)
    actual must beEqualTo(expected)
  }

  def e4 = {
    val input =
      Input(
        None,
        None,
        None,
        None,
        None,
        None,
        None
      )
    val expected: Result =
      ef(
        FailureDetails.EnrichmentFailureMessage.InputData(
          "collector_tstamp",
          None,
          "missing"
        )
      ).invalidNel
    val actual = runEnrichment(input)
    actual must beEqualTo(expected)
  }

  def e5 = {
    val input =
      Input(
        Some("GBP"),
        None,
        None,
        None,
        Some("GBP"),
        None,
        None
      )
    val expected: Result =
      ef(
        FailureDetails.EnrichmentFailureMessage
          .InputData("collector_tstamp", None, "missing")
      ).invalidNel
    val actual = runEnrichment(input)
    actual must beEqualTo(expected)
  }

  def e6 = {
    val input =
      Input(
        Some("GBP"),
        Some(11.00),
        None,
        None,
        None,
        None,
        Some(coTstamp)
      )
    val expected: Result = (Some(new BigDecimal("12.75")), None, None, None).valid
    val actual = runEnrichment(input)
    actual must beEqualTo(expected)
  }

  def e7 = {
    val input =
      Input(
        Some("GBP"),
        None,
        Some(2.67),
        Some(0.00),
        None,
        None,
        Some(coTstamp)
      )
    val expected: Result = (None, Some(new BigDecimal("3.09")), Some(new BigDecimal("0.00")), None).valid
    val actual = runEnrichment(input)
    actual must beEqualTo(expected)
  }

  def e8 = {
    val input =
      Input(
        None,
        None,
        None,
        None,
        Some("GBP"),
        Some(12.99),
        Some(coTstamp)
      )
    val expected: Result = (None, None, None, Some(new BigDecimal("15.05"))).valid
    val actual = runEnrichment(input)
    actual must beEqualTo(expected)
  }

  def e9 = {
    val input =
      Input(
        Some("GBP"),
        Some(11.00),
        Some(2.67),
        Some(0.00),
        None,
        None,
        Some(coTstamp)
      )
    val expected: Result = (Some(new BigDecimal("12.75")), Some(new BigDecimal("3.09")), Some(new BigDecimal("0.00")), None).valid
    val actual = runEnrichment(input)
    actual must beEqualTo(expected)
  }

  def e10 = {
    val input =
      Input(
        None,
        Some(14.00),
        Some(4.67),
        Some(0.00),
        Some("GBP"),
        Some(10.99),
        Some(coTstamp)
      )
    val expected: Result =
      (None, None, None, Some(new BigDecimal("12.74"))).valid
    val actual = runEnrichment(input)
    actual must beEqualTo(expected)
  }

  def e11 = {
    val input =
      Input(
        None,
        Some(11.00),
        Some(2.67),
        Some(0.00),
        None,
        Some(12.99),
        Some(coTstamp)
      )
    val expected: Result = (None, None, None, None).valid
    val actual = runEnrichment(input)
    actual must beEqualTo(expected)
  }

  def e12 = {
    val input =
      Input(
        Some("EUR"),
        Some(11.00),
        Some(2.67),
        Some(0.00),
        Some("EUR"),
        Some(12.99),
        Some(coTstamp)
      )
    val expected: Result =
      (
        Some(new BigDecimal("11.00")),
        Some(new BigDecimal("2.67")),
        Some(new BigDecimal("0.00")),
        Some(new BigDecimal("12.99"))
      ).valid
    val actual = runEnrichment(input)
    actual must beEqualTo(expected)
  }

  def e13 = {
    val input =
      Input(
        Some("GBP"),
        Some(16.00),
        Some(2.67),
        Some(0.00),
        None,
        Some(10.00),
        Some(coTstamp)
      )
    val expected: Result = (Some(new BigDecimal("18.54")), Some(new BigDecimal("3.09")), Some(new BigDecimal("0.00")), None).valid
    val actual = runEnrichment(input)
    actual must beEqualTo(expected)
  }
}

object CurrencyConversionEnrichmentSpec {
  val OerApiKey = "OER_KEY"

  lazy val validAppKey = sys.env
    .getOrElse(OerApiKey,
               throw new IllegalStateException(
                 s"No $OerApiKey environment variable found, test should have been skipped"
               )
    )
  type Result = ValidatedNel[
    FailureDetails.EnrichmentFailure,
    (Option[BigDecimal], Option[BigDecimal], Option[BigDecimal], Option[BigDecimal])
  ]
  val schemaKey = SchemaKey("vendor", "name", "format", SchemaVer.Full(1, 0, 0))
  val ef: FailureDetails.EnrichmentFailureMessage => FailureDetails.EnrichmentFailure = m =>
    FailureDetails.EnrichmentFailure(
      FailureDetails.EnrichmentInformation(schemaKey, "currency-conversion").some,
      m
    )
  val coTstamp: DateTime = new DateTime(2011, 3, 13, 0, 0)

  case class Input(
    trCurrency: Option[String],
    trTotal: Option[Double],
    trTax: Option[Double],
    trShipping: Option[Double],
    tiCurrency: Option[String],
    tiPrice: Option[Double],
    collectorTstamp: Option[DateTime]
  )

  def runEnrichment(
    input: Input,
    apiKey: String = validAppKey
  ) =
    for {
      e <- CurrencyConversionConf(schemaKey, DeveloperAccount, apiKey, CurrencyUnit.EUR)
             .enrichment[Id]
      res <- e.convertCurrencies(
               input.trCurrency,
               input.trTotal,
               input.trTax,
               input.trShipping,
               input.tiCurrency,
               input.tiPrice,
               input.collectorTstamp
             )
    } yield res
}
