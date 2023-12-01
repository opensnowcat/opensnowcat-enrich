/*
 * Copyright (c) 2022-2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.common.fs2.blackbox.enrichments

import io.circe.literal._

import org.specs2.mutable.Specification

import cats.effect.testing.specs2.CatsIO

import cats.syntax.option._

import com.snowplowanalytics.snowplow.enrich.common.fs2.blackbox.BlackBoxTesting

class CurrencyConversionEnrichmentTransactionSpec extends Specification with CatsIO {

  args(skipAll = !sys.env.contains("OER_KEY"))

  "enrichWith" should {
    "enrich with CurrencyConversionEnrichment" in {
      val querystring =
        "e=tr&tr_id=order-123&tr_af=pb&tr_tt=8000&tr_tx=200&tr_sh=50&tr_ci=London&tr_st=England&tr_co=UK&tr_cu=USD&tid=028288"
      val input = BlackBoxTesting.buildCollectorPayload(
        path = "/ice.png",
        querystring = querystring.some,
        timestamp = 1562008983000L
      )
      val expected = Map(
        "event_vendor" -> "com.snowplowanalytics.snowplow",
        "event_name" -> "transaction",
        "event_format" -> "jsonschema",
        "event_version" -> "1-0-0",
        "event" -> "transaction",
        "base_currency" -> "EUR",
        "tr_currency" -> "USD",
        "tr_affiliation" -> "pb",
        "tr_total" -> "8000",
        "tr_total_base" -> "7087.49",
        "tr_tax" -> "200",
        "tr_tax_base" -> "177.19",
        "tr_shipping" -> "50",
        "tr_shipping_base" -> "44.30",
        "tr_orderid" -> "order-123",
        "tr_state" -> "England",
        "txn_id" -> "28288",
        "tr_country" -> "UK",
        "tr_city" -> "London",
        "collector_tstamp" -> "2019-07-01 19:23:03.000"
      )
      BlackBoxTesting.runTest(input, expected, Some(CurrencyConversionEnrichmentTransactionSpec.conf))
    }
  }
}

object CurrencyConversionEnrichmentTransactionSpec {
  val conf = json"""
    {
      "schema": "iglu:com.snowplowanalytics.snowplow/currency_conversion_config/jsonschema/1-0-0",
      "data": {
        "enabled": true,
        "vendor": "com.snowplowanalytics.snowplow",
        "name": "currency_conversion_config",
        "parameters": {
          "accountType": "DEVELOPER",
          "apiKey": ${sys.env.get("OER_KEY").getOrElse("-")},
          "baseCurrency": "EUR",
          "rateAt": "EOD_PRIOR"
        }
      }
    }
  """
}
