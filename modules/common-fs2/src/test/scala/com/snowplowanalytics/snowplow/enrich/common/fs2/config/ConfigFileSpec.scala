/*
 * Copyright (c) 2020-2023 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.common.fs2.config

import java.nio.file.Paths

import cats.syntax.either._
import cats.effect.IO

import cats.effect.testing.specs2.CatsIO

import com.typesafe.config.ConfigFactory

import org.specs2.mutable.Specification

class ConfigFileSpec extends Specification with CatsIO {
  "parse" should {
    "parse valid 0 minutes as None" in {
      val input =
        """{
          "input": {
            "type": "PubSub",
            "subscription": "projects/test-project/subscriptions/inputSub",
            "parallelPullCount": 1,
            "maxQueueSize": 3000,
            "maxRequestBytes": 50000000,
            "maxAckExtensionPeriod": 1 hour,
            "gcpUserAgent": {
              "productName": "Snowplow OSS"
            }
          },
          "output": {
            "good": {
              "type": "PubSub",
              "topic": "projects/test-project/topics/good-topic",
              "delayThreshold": "200 milliseconds",
              "maxBatchSize": 1000,
              "maxBatchBytes": 8000000,
              "gcpUserAgent": {
                "productName": "Snowplow OSS"
              }
            },
            "pii": {
              "type": "PubSub",
              "topic": "projects/test-project/topics/pii-topic",
              "delayThreshold": "200 milliseconds",
              "maxBatchSize": 1000,
              "maxBatchBytes": 8000000,
              "gcpUserAgent": {
                "productName": "Snowplow OSS"
              }
            },
            "bad": {
              "type": "PubSub",
              "topic": "projects/test-project/topics/bad-topic",
              "delayThreshold": "200 milliseconds",
              "maxBatchSize": 1000,
              "maxBatchBytes": 8000000,
              "gcpUserAgent": {
                "productName": "Snowplow OSS"
              }
            }
          },
          "concurrency": {
            "enrich": 256,
            "sink": 3
          },
          "assetsUpdatePeriod": "0 minutes",
          "remoteAdapters": {
            "connectionTimeout": "10 seconds",
            "readTimeout": "45 seconds",
            "maxConnections": 10,
            "configs": []
          },
          "metricsReportPeriod": "10 second",
          "telemetry": {
            "disable": false,
            "interval": "15 minutes",
            "method": "POST",
            "collectorUri": "sp.snowcatcloud.com",
            "collectorPort": "443",
            "secure": true
          },
          "featureFlags" : {
            "acceptInvalid": false,
            "legacyEnrichmentOrder": false,
            "tryBase64Decoding": false
          },
          "experimental": {
            "metadata": {
               "endpoint": "https://my_pipeline.my_domain.com/iglu",
               "interval": "15 minutes",
               "organizationId": "c5f3a09f-75f8-4309-bec5-fea560f78455",
               "pipelineId": "75a13583-5c99-40e3-81fc-541084dfc784"
            }
          },
          "blobStorage": {
            "gcs": true
            "s3": true
          }
        }"""

      ConfigFile.parse[IO](Base64Hocon(ConfigFactory.parseString(input)).asLeft).value.map {
        case Left(message) => message must contain("assetsUpdatePeriod in config file cannot be less than 0")
        case _ => ko("Decoding should have failed")
      }
    }

    "resolve parameter substitutions in hocon ${} syntax" in {
      @annotation.nowarn("msg=possible missing interpolator")
      val input =
        """{
          
          "testSubstitutions": {
            "a": "test-substituted-collector-uri"
            "b": 42
          }

          "telemetry": {
            #### SUBSTITUTED VALUES MUST GET PROPERLY RESOLVED: ####
            ##
            "collectorUri": ${testSubstitutions.a}
            "collectorPort": ${testSubstitutions.b}
            ##
            ########################################################
          }


          "input": {
            "type": "FileSystem"
            "dir": "/path/to/input"
          },
          "output": {
            "good": {
              "type": "FileSystem"
              "file": "/path/to/good"
            },
            "bad": {
              "type": "FileSystem"
              "file": "/path/to/bad"
            }
          },
          "concurrency": {
            "enrich": 256
            "sink": 3
          },
          "remoteAdapters": {
            "connectionTimeout": "10 seconds"
            "readTimeout": "45 seconds"
            "maxConnections": 10
            "configs": []
          },
          "telemetry": {
            "disable": false
            "interval": "15 minutes"
            "method": "POST"
            "secure": true
          },
          "featureFlags" : {
            "acceptInvalid": false,
            "legacyEnrichmentOrder": false,
            "tryBase64Decoding": false
          },
          "blobStorage": {
            "gcs": true,
            "s3": true
          }
        }"""

      ConfigFile.parse[IO](Base64Hocon(ConfigFactory.parseString(input)).asLeft).value.map { result =>
        result must beRight.like {
          case configFile =>
            configFile.telemetry.collectorUri must_== "test-substituted-collector-uri"
            configFile.telemetry.collectorPort must_== 42
        }
      }
    }

    "not throw an exception if file not found" in {
      val configPath = Paths.get("does-not-exist")
      ConfigFile.parse[IO](configPath.asRight).value.map(result => result must beLeft)
    }
  }
}
