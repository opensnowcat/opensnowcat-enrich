/*
 * Copyright (c) 2023-2023 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.kinesis.enrichments

import java.util.Base64

import com.snowplowanalytics.iglu.core.{SchemaKey, SchemaVer}

case object Javascript extends Enrichment {
  private val base64Encoder = Base64.getEncoder()

  val script = """
    const cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
    const generator = javax.crypto.KeyGenerator.getInstance("AES")
    generator.init(new java.security.SecureRandom())
    const key = generator.generateKey()
    cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.IvParameterSpec(key.getEncoded()))

    function process(event){
      var appId = event.getApp_id()
      if (appId == null) {
        return []
      } else {
        return [ {
          schema: "iglu:com.snowplowanalytics.snowplow/identify/jsonschema/1-0-0",
          data: { id:  new java.lang.String(cipher.doFinal(appId.getBytes())) }
        } ]
      }
    }
  """
  val config = s"""
    {
      "schema": "iglu:com.snowplowanalytics.snowplow/javascript_script_config/jsonschema/1-0-0",
      "data": {
        "vendor": "com.snowplowanalytics.snowplow",
        "name": "javascript_script_config",
        "enabled": true,
        "parameters": {
          "script": "${new String(base64Encoder.encode(script.getBytes()))}"
        }
      }
    }
  """
  val outputSchema = SchemaKey(
    "com.snowplowanalytics.snowplow",
    "identify",
    "jsonschema",
    SchemaVer.Full(1, 0, 0)
  )
}
