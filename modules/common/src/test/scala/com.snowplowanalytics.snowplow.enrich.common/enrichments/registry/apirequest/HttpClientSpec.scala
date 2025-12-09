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
package com.snowplowanalytics.snowplow.enrich.common.utils

import org.http4s.{BasicCredentials, Header, Headers}
import org.http4s.headers.Authorization
import org.typelevel.ci.CIString
import org.specs2.Specification
import org.specs2.matcher.ValidatedMatchers
import org.specs2.mock.Mockito

class HttpClientSpec extends Specification with ValidatedMatchers with Mockito {
  def is = s2"""
  getHeaders returns Authorization, accept header and content-type headers if authUser or authPassword is defined $e1
  getHeaders does not return an Authorization header if authUser and authPassword are not defined  $e2
  """

  def e1 = {
    val headers = HttpClient.getHeaders(None, Some("2778e1d8-500b-4f9f-a14e-f68b6b4e7b9f"))
    val expected =
      Headers(
        Authorization(BasicCredentials("", "2778e1d8-500b-4f9f-a14e-f68b6b4e7b9f")),
        Header.Raw(CIString("content-type"), "application/json"),
        Header.Raw(CIString("accept"), "*/*")
      )
    headers must beEqualTo(expected)
  }

  def e2 = {
    val headers = HttpClient.getHeaders(None, None)
    val expected = Headers(Header.Raw(CIString("content-type"), "application/json"), Header.Raw(CIString("accept"), "*/*"))
    headers must beEqualTo(expected)
  }
}
