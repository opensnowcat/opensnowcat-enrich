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

import java.net.InetAddress

import cats.Id
import cats.syntax.functor._

import io.circe.literal._

import org.joda.time.DateTime
import com.snowplowanalytics.iglu.core.{SchemaKey, SchemaVer, SelfDescribingData}

import inet.ipaddr.HostName
import org.specs2.Specification
import org.specs2.matcher.DataTables

class IabEnrichmentSpec extends Specification with DataTables {

  def is = s2"""
  performCheck should correctly perform IAB checks on valid input $e1
  getIabContext should return a valid JObject on valid input      $e2
  """

  // When testing, localMode is set to true, so the URIs are ignored and the databases are loaded from test/resources
  val validConfig = IabEnrichment
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
    .get

  def e1 =
    "SPEC NAME" || "USER AGENT" | "IP ADDRESS" | "EXPECTED SPIDER OR ROBOT" | "EXPECTED CATEGORY" | "EXPECTED REASON" | "EXPECTED PRIMARY IMPACT" |
      "valid UA/IP" !! "Xdroid" ! "192.168.0.1".ip ! false ! "BROWSER" ! "PASSED_ALL" ! "NONE" |
      "valid UA, excluded IP" !! "Mozilla/5.0" ! "192.168.151.21".ip ! true ! "SPIDER_OR_ROBOT" ! "FAILED_IP_EXCLUDE" ! "UNKNOWN" |
      "invalid UA, excluded IP" !! "xonitor" ! "192.168.0.1".ip ! true ! "SPIDER_OR_ROBOT" ! "FAILED_UA_INCLUDE" ! "UNKNOWN" |> {
      (
        _,
        userAgent,
        ipAddress,
        expectedSpiderOrRobot,
        expectedCategory,
        expectedReason,
        expectedPrimaryImpact
      ) =>
        validConfig.enrichment[Id].map { e =>
          e.performCheck(userAgent, ipAddress, DateTime.now()) must beRight.like {
            case check =>
              check.spiderOrRobot must_== expectedSpiderOrRobot and
                (check.category must_== expectedCategory) and
                (check.reason must_== expectedReason) and
                (check.primaryImpact must_== expectedPrimaryImpact)
          }
        }
    }

  def e2 = {
    val responseJson =
      SelfDescribingData(
        SchemaKey("com.iab.snowplow", "spiders_and_robots", "jsonschema", SchemaVer.Full(1, 0, 0)),
        json"""{"spiderOrRobot": false, "category": "BROWSER", "reason": "PASSED_ALL", "primaryImpact": "NONE"}"""
      )
    validConfig
      .enrichment[Id]
      .map(_.getIabContext("Xdroid", "192.168.0.1".ip, DateTime.now())) must
      beRight(responseJson)
  }

  private implicit class IpOps(s: String) {
    def ip: InetAddress =
      new HostName(s).toInetAddress
  }
}
