/**
 * Copyright (c) 2019-2022 Snowplow Analytics Ltd. All rights reserved.
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

import io.circe.literal._

import nl.basjes.parse.useragent.UserAgent
import com.snowplowanalytics.iglu.core.{SchemaKey, SchemaVer, SelfDescribingData}

import org.specs2.matcher.ValidatedMatchers
import org.specs2.mutable.Specification

class YauaaEnrichmentSpec extends Specification with ValidatedMatchers {

  import YauaaEnrichment.decapitalize

  /** Default enrichment with 1-0-0 context */
  val yauaaEnrichment = YauaaEnrichment(None)

  // Devices
  val uaIpad =
    "Mozilla/5.0 (iPad; CPU OS 6_1_3 like Mac OS X) AppleWebKit/536.26 (KHTML, like Gecko) Version/6.0 Mobile/10B329 Safari/8536.25"
  val uaIphoneX =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 11_0 like Mac OS X) AppleWebKit/604.1.38 (KHTML, like Gecko) Version/11.0 Mobile/15A372 Safari/604.1"
  val uaIphone7 =
    "Mozilla/5.0 (iPhone9,3; U; CPU iPhone OS 10_0_1 like Mac OS X) AppleWebKit/602.1.50 (KHTML, like Gecko) Version/10.0 Mobile/14A403 Safari/602.1"
  val uaGalaxyTab =
    "Mozilla/5.0 (Linux; U; Android 2.2; fr-fr; GT-P1000 Build/FROYO) AppleWebKit/533.1 (KHTML, like Gecko) Version/4.0 Mobile Safari/533.1"
  val uaGalaxyS9 =
    "Mozilla/5.0 (Linux; Android 8.0.0; SM-G960F Build/R16NW) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/62.0.3202.84 Mobile Safari/537.36"
  val uaGalaxyS8 =
    "Mozilla/5.0 (Linux; Android 7.0; SM-G892A Build/NRD90M; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/60.0.3112.107 Mobile Safari/537.36"
  val uaXperiaXZ =
    "Mozilla/5.0 (Linux; Android 7.1.1; G8231 Build/41.2.A.0.219; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/59.0.3071.125 Mobile Safari/537.36"
  val uaNexusOne =
    "Mozilla/5.0 (Linux; U; Android 2.2; en-us; Nexus One Build/FRF91) AppleWebKit/533.1 (KHTML, like Gecko) Version/4.0 Mobile Safari/533.1"
  val uaNexusS =
    "Mozilla/5.0 (Linux; Android 4.1.2; Nexus S Build/JZO54K) AppleWebKit/535.19 (KHTML, like Gecko) Chrome/18.0.1025.166 Mobile Safari/535.19"
  val uaPlaystation4 = "Mozilla/5.0 (PlayStation 4 1.52) AppleWebKit/536.26 (KHTML, like Gecko)"

  // Browsers
  val uaChrome13 =
    "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/535.1 (KHTML, like Gecko) Chrome/13.0.782.112 Safari/535.1"
  val uaChrome106 =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/106.0.0.0 Safari/537.36"
  val uaChromium =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.22 (KHTML, like Gecko) Ubuntu Chromium/25.0.1364.160 Chrome/25.0.1364.160 Safari/537.22"
  val uaFirefox = "Mozilla/5.0 (Windows NT 6.1; rv:2.0b7pre) Gecko/20100921 Firefox/4.0b7pre"
  val uaIE = "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.0; Trident/5.0)"
  val uaOpera =
    "Mozilla/4.0 (compatible; MSIE 6.0; MSIE 5.5; Windows NT 5.0) Opera 7.02 Bork-edition [en]"
  val uaSafari =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_4) AppleWebKit/536.30.1 (KHTML, like Gecko) Version/6.0.5 Safari/536.30.1"

  // Google bot
  val uaGoogleBot = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

  "YAUAA enrichment should" >> {
    "return default value for null" >> {
      yauaaEnrichment.analyzeUserAgent(null, Nil) shouldEqual YauaaEnrichment.DefaultResult
    }

    "return default value for empty user agent" >> {
      yauaaEnrichment.analyzeUserAgent("", Nil) shouldEqual YauaaEnrichment.DefaultResult
    }

    "detect correctly DeviceClass" >> {
      checkYauaaParsingForField(
        Map(
          uaChromium -> "Desktop",
          uaGalaxyTab -> "Tablet",
          uaIpad -> "Tablet",
          uaNexusS -> "Phone",
          uaPlaystation4 -> "Game Console",
          uaGoogleBot -> "Robot"
        ),
        UserAgent.DEVICE_CLASS
      )
    }

    "detect correctly DeviceName" >> {
      checkYauaaParsingForField(
        Map(
          uaGalaxyS9 -> "Samsung SM-G960F",
          uaGalaxyS8 -> "Samsung SM-G892A",
          uaXperiaXZ -> "Sony G8231",
          uaIphone7 -> "Apple iPhone",
          uaIphoneX -> "Apple iPhone",
          uaNexusS -> "Google Nexus S",
          uaNexusOne -> "Google Nexus ONE",
          uaPlaystation4 -> "Sony PlayStation 4",
          uaGoogleBot -> "Google"
        ),
        UserAgent.DEVICE_NAME
      )
    }

    "detect correctly OperatingSystemClass" >> {
      checkYauaaParsingForField(
        Map(
          uaChromium -> "Desktop",
          uaIE -> "Desktop",
          uaIpad -> "Mobile",
          uaNexusS -> "Mobile",
          uaPlaystation4 -> "Game Console",
          uaGoogleBot -> "Cloud"
        ),
        UserAgent.OPERATING_SYSTEM_CLASS
      )
    }

    "detect correctly OperatingSystemName" >> {
      checkYauaaParsingForField(
        Map(
          uaChromium -> "Ubuntu",
          uaIE -> "Windows NT",
          uaNexusOne -> "Android",
          uaIphoneX -> "iOS",
          uaIpad -> "iOS",
          uaSafari -> "Mac OS",
          uaGoogleBot -> "Google Cloud"
        ),
        UserAgent.OPERATING_SYSTEM_NAME
      )
    }

    "detect correctly LayoutEngineClass" >> {
      checkYauaaParsingForField(
        Map(
          uaChrome13 -> "Browser",
          uaIE -> "Browser",
          uaNexusOne -> "Browser",
          uaIphoneX -> "Browser",
          uaIpad -> "Browser",
          uaSafari -> "Browser",
          uaPlaystation4 -> "Browser",
          uaGoogleBot -> "Robot"
        ),
        UserAgent.LAYOUT_ENGINE_CLASS
      )
    }

    "detect correctly AgentClass" >> {
      checkYauaaParsingForField(
        Map(
          uaChromium -> "Browser",
          uaIE -> "Browser",
          uaNexusOne -> "Browser",
          uaIphoneX -> "Browser",
          uaIpad -> "Browser",
          uaSafari -> "Browser",
          uaPlaystation4 -> "Browser",
          uaGoogleBot -> "Robot"
        ),
        UserAgent.AGENT_CLASS
      )
    }

    "detect correctly AgentName" >> {
      checkYauaaParsingForField(
        Map(
          uaChrome13 -> "Chrome",
          uaChromium -> "Chromium",
          uaFirefox -> "Firefox",
          uaIE -> "Internet Explorer",
          uaNexusOne -> "Stock Android Browser",
          uaIphoneX -> "Safari",
          uaIpad -> "Safari",
          uaOpera -> "Opera",
          uaSafari -> "Safari",
          uaGoogleBot -> "Googlebot"
        ),
        UserAgent.AGENT_NAME
      )
    }

    "use client hint headers to aid parsing" >> {
      val clientHintHeader =
        """Sec-CH-UA-Full-Version-List: "Chromium";v="106.0.5249.119", "Google Chrome";v="106.0.5249.119", "Not;A=Brand";v="99.0.0.0""""
      yauaaEnrichment.analyzeUserAgent(uaChrome106, Nil)("agentNameVersion") shouldEqual "Chrome 106"
      yauaaEnrichment.analyzeUserAgent(uaChrome106, List(clientHintHeader))("agentNameVersion") shouldEqual "Chrome 106.0.5249.119"
    }

    /** Resembles the case when `ua` was sent as a field in the tp2 payload */
    "Prioritize explicitly passed user agent over an http header" >> {
      val headers = List("User-Agent: curl/7.54")
      yauaaEnrichment.analyzeUserAgent(uaFirefox, headers)("agentName") shouldEqual "Firefox"
    }

    "create a JSON with the schema 1-0-4 and the data" >> {
      val expected =
        SelfDescribingData(
          YauaaEnrichment.outputSchema,
          json"""{
            "deviceBrand":"Samsung",
            "deviceName":"Samsung SM-G960F",
            "layoutEngineNameVersion":"Blink 62.0",
            "operatingSystemNameVersion":"Android 8.0.0",
            "agentInformationEmail" : "Unknown",
            "networkType" : "Unknown",
            "operatingSystemVersionBuild":"R16NW",
            "webviewAppNameVersionMajor" : "Unknown ??",
            "layoutEngineNameVersionMajor":"Blink 62",
            "operatingSystemName":"Android",
            "agentVersionMajor":"62",
            "layoutEngineVersionMajor":"62",
            "webviewAppName" : "Unknown",
            "deviceClass":"Phone",
            "agentNameVersionMajor":"Chrome 62",
            "webviewAppVersionMajor" : "??",
            "operatingSystemClass":"Mobile",
            "webviewAppVersion" : "??",
            "layoutEngineName":"Blink",
            "agentName":"Chrome",
            "agentVersion":"62.0.3202.84",
            "layoutEngineClass":"Browser",
            "agentNameVersion":"Chrome 62.0.3202.84",
            "operatingSystemVersion":"8.0.0",
            "agentClass":"Browser",
            "layoutEngineVersion":"62.0",
            "operatingSystemNameVersionMajor":"Android 8",
            "operatingSystemVersionMajor":"8"
          }"""
        )
      val actual = yauaaEnrichment.getYauaaContext(uaGalaxyS9, Nil)
      actual shouldEqual expected

      val defaultJson =
        SelfDescribingData(
          YauaaEnrichment.outputSchema,
          json"""{"deviceClass":"Unknown"}"""
        )
      yauaaEnrichment.getYauaaContext("", Nil) shouldEqual defaultJson
    }

    "never add __SyntaxError__ to the context" >> {
      val ua =
        "useragent=Mozilla/5.0 (Linux armv7l) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/38.0.2125.122 Safari/537.36 OPR/25.0.1620.0 OMI/4.3.18.7.Dominik.0 VSTVB MB100 HbbTV/1.2.1 (; PANASONIC; MB100; 0.1.34.28; ;) SmartTvA/3.0.0 UID (00:09:DF:A7:74:6B/MB100/PANASONIC/0.1.34.28)"
      yauaaEnrichment.analyzeUserAgent(ua, Nil).contains("__SyntaxError__") shouldEqual false
    }
  }

  /** Helper to check that a certain field of a parsed user agent has the expected value. */
  def checkYauaaParsingForField(expectedResults: Map[String, String], fieldName: String) =
    expectedResults.map {
      case (userAgent, expectedField) =>
        yauaaEnrichment.analyzeUserAgent(userAgent, Nil)(decapitalize(fieldName)) shouldEqual expectedField
    }.toList

  "decapitalize should" >> {
    "let an empty string as is" >> {
      decapitalize("") shouldEqual ""
    }

    "lower the unique character of a string" >> {
      decapitalize("A") shouldEqual "a"
      decapitalize("b") shouldEqual "b"
    }

    "lower only the first letter of a string" >> {
      decapitalize("FooBar") shouldEqual "fooBar"
      decapitalize("Foo Bar") shouldEqual "foo Bar"
      decapitalize("fooBar") shouldEqual "fooBar"
    }
  }

  "Parsing the config JSON for YAUAA enrichment" should {

    val schemaKey = SchemaKey(
      YauaaEnrichment.supportedSchema.vendor,
      YauaaEnrichment.supportedSchema.name,
      YauaaEnrichment.supportedSchema.format,
      SchemaVer.Full(1, 0, 0)
    )

    "successfully construct a YauaaEnrichment case class with the right cache size if specified" in {
      val cacheSize = 42

      val yauaaConfigJson = json"""{
        "enabled": true,
        "parameters": {
          "cacheSize": $cacheSize
        }
      }"""

      val expected = EnrichmentConf.YauaaConf(schemaKey, Some(cacheSize))
      val actual = YauaaEnrichment.parse(yauaaConfigJson, schemaKey)
      actual must beValid(expected)
    }

    "successfully construct a YauaaEnrichment case class with a default cache size if none specified" in {
      val yauaaConfigJson = json"""{"enabled": true }"""

      val expected = EnrichmentConf.YauaaConf(schemaKey, None)
      val actual = YauaaEnrichment.parse(yauaaConfigJson, schemaKey)
      actual must beValid(expected)
    }
  }
}
