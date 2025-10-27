/*
 * Copyright (c) 2025 SnowcatCloud, Inc. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.kafka

import org.specs2.mutable.Specification
import com.snowplowanalytics.snowplow.enrich.common.outputs.EnrichedEvent
import com.snowplowanalytics.snowplow.enrich.common.utils.ConversionUtils

class SinkSpec extends Specification {

  "extractHostFromBadRow" should {
    "extract host from badrow with headers" in {
      val host = "localhost"
      val badRowWithKeyword =
        s"""{"schema":"iglu:com.snowplowanalytics.snowplow.badrows/tracker_protocol_violations/jsonschema/1-0-1","data":{"payload":{"headers":["Host: $host","User-Agent: test"]}}}"""

      val result = Sink.extractHostFromBadRow(badRowWithKeyword)

      result must beSome(host)
    }
  }

  "resolveTopicName" should {
    "return None when mapping is empty" in {
      val data = "some\ttsv\tdata".getBytes
      val mapping = Map.empty[String, String]

      val result = Sink.resolveTopicName(data, mapping)

      result must beNone
    }

    "extract host from good event with http_header context and map to topic" in {
      val host = "localhost:8080"
      val mappedTopic = "mapped-topic"
      val mapping = Map(host -> mappedTopic)

      val derivedContexts = s"""{
        "schema": "iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1",
        "data": [
          {
            "schema": "iglu:nl.basjes/yauaa_context/jsonschema/1-0-4",
            "data": {
              "deviceClass": "Unknown"
            }
          },
          {
            "schema": "iglu:org.ietf/http_header/jsonschema/1-0-0",
            "data": {
              "name": "Host",
              "value": "$host"
            }
          },
          {
            "schema": "iglu:org.ietf/http_header/jsonschema/1-0-0",
            "data": {
              "name": "User-Agent",
              "value": "Grafana k6/1.3.0"
            }
          }
        ]
      }"""

      val enrichedEvent = new EnrichedEvent()
      enrichedEvent.derived_contexts = derivedContexts
      
      val tsvEvent = ConversionUtils.tabSeparatedEnrichedEvent(enrichedEvent)
      val data = tsvEvent.getBytes

      val result = Sink.resolveTopicName(data, mapping)

      result must beSome(mappedTopic)
    }

    "return None when http_header context is not present" in {
      val host = "localhost"
      val mappedTopic = "mapped-topic"
      val mapping = Map(host -> mappedTopic)

      val derivedContexts = """{
        "schema": "iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-0",
        "data": []
      }"""

      val enrichedEvent = new EnrichedEvent()
      enrichedEvent.derived_contexts = derivedContexts
      
      val tsvEvent = ConversionUtils.tabSeparatedEnrichedEvent(enrichedEvent)
      val data = tsvEvent.getBytes

      val result = Sink.resolveTopicName(data, mapping)

      result must beNone
    }

    "return None when host is not in mapping" in {
      val eventHost = "example.com"
      val mappedHost = "localhost"
      val mappedTopic = "mapped-topic"
      val mapping = Map(mappedHost -> mappedTopic)

      val derivedContexts = s"""{
        "schema": "iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-0",
        "data": [
          {
            "schema": "iglu:org.ietf/http_header/jsonschema/1-0-0",
            "data": {
              "Host": "$eventHost"
            }
          }
        ]
      }"""

      val enrichedEvent = new EnrichedEvent()
      enrichedEvent.derived_contexts = derivedContexts
      
      val tsvEvent = ConversionUtils.tabSeparatedEnrichedEvent(enrichedEvent)
      val data = tsvEvent.getBytes

      val result = Sink.resolveTopicName(data, mapping)

      result must beNone
    }

    "extract host from bad row and map to topic" in {
      val host = "localhost:8080"
      val mappedTopic = "mapped-topic"
      val mapping = Map(host -> mappedTopic)

      val badRow = s"""{
        "schema": "iglu:com.snowplowanalytics.snowplow.badrows/tracker_protocol_violations/jsonschema/1-0-1",
        "data": {
          "processor": {
            "artifact": "opensnowcat-enrich-kafka",
            "version": "1.2.7"
          },
          "failure": {
            "timestamp": "2025-10-19T04:52:25.098227Z",
            "messages": []
          },
          "payload": {
            "vendor": "com.snowplowanalytics.snowplow",
            "version": "tp2",
            "headers": [
              "Timeout-Access: <function1>",
              "Host: $host",
              "User-Agent: curl/8.7.1",
              "Accept: */*"
            ],
            "networkUserId": "a749479b-f971-4ece-9a8c-2b761fcc9dd2"
          }
        }
      }"""

      val data = badRow.getBytes

      val result = Sink.resolveTopicName(data, mapping)

      result must beSome(mappedTopic)
    }

    "return None for bad row when badrows keyword is not present" in {
      val host = "localhost"
      val mappedTopic = "mapped-topic"
      val mapping = Map(host -> mappedTopic)

      val notABadRow = """{"some": "json"}"""
      val data = notABadRow.getBytes

      val result = Sink.resolveTopicName(data, mapping)

      result must beNone
    }
  }
}
