/*
 * Copyright (c) 2016-2022 Snowplow Analytics Ltd. All rights reserved.
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
package adapters
package registry

import cats.data.NonEmptyList
import cats.syntax.option._
import com.snowplowanalytics.snowplow.badrows._
import org.joda.time.DateTime
import org.specs2.Specification
import org.specs2.matcher.{DataTables, ValidatedMatchers}
import io.circe.parser._

import loaders._
import utils.Clock._

import SpecHelpers._

class MailgunAdapterSpec extends Specification with DataTables with ValidatedMatchers {
  def is = s2"""
  toRawEvents must return a Success Nel if every event 'delivered' in the payload is successful                $e1
  toRawEvents must return a Success Nel if every event 'opened' in the payload is successful                   $e2
  toRawEvents must return a Success Nel if every event 'clicked' in the payload is successful                  $e3
  toRawEvents must return a Success Nel if every event 'unsubscribed' in the payload is successful             $e4
  toRawEvents must return a Success Nel if the content type is 'application/json' and parsing is successful    $e5
  toRawEvents must return a Nel Failure if the request body is missing                                         $e6
  toRawEvents must return a Nel Failure if the content type is missing                                         $e7
  toRawEvents must return a Nel Failure if the content type is incorrect                                       $e8
  toRawEvents must return a Failure Nel if the request body is empty                                           $e9
  toRawEvents must return a Failure if the request body does not contain an event parameter                    $e10
  toRawEvents must return a Failure if the event type is not recognized                                        $e11
  payloadBodyToEvent must return a Failure if the event data is missing 'timestamp'                            $e12
  payloadBodyToEvent must return a Failure if the event data is missing 'token'                                $e13
  payloadBodyToEvent must return a Failure if the event data is missing 'signature'                            $e14
  """

  object Shared {
    val api = CollectorPayload.Api("com.mailgun", "v1")
    val cljSource = CollectorPayload.Source("clj-tomcat", "UTF-8", None)
    val context = CollectorPayload.Context(
      DateTime.parse("2013-08-29T00:18:48.000+00:00").some,
      "37.157.33.123".some,
      None,
      None,
      Nil,
      None
    )
  }

  val adapterWithDefaultSchemas = MailgunAdapter(schemas = mailgunSchemas)
  val ContentType = "application/json"

  def e1 = {
    val body =
      """{"signature":{"token":"090e1ce3702378c8121f3765a8efe0ffb97c4e2ca2adda6729","timestamp":"1657907833","signature":"496413b238ab6affce021b850d3fc72c4832fb04f1aed6d4a2fda7d08e6e95a6"},"event-data":{"id":"CPgfbmQMTCKtHW6uIWtuVe","timestamp":1521472262.908181,"log-level":"info","event":"delivered","delivery-status":{"tls":true,"mx-host":"smtp-in.example.com","code":250,"description":"","session-seconds":0.4331989288330078,"utf8":true,"attempt-no":1,"message":"OK","certificate-verified":true},"flags":{"is-routed":false,"is-authenticated":true,"is-system-test":false,"is-test-mode":false},"envelope":{"transport":"smtp","sender":"bob@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org","sending-ip":"209.61.154.250","targets":"alice@example.com"},"message":{"headers":{"to":"Alice <alice@example.com>","message-id":"20130503182626.18666.16540@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org","from":"Bob <bob@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org>","subject":"Test delivered webhook"},"attachments":[],"size":111},"recipient":"alice@example.com","recipient-domain":"example.com","storage":{"url":"https://se.api.mailgun.net/v3/domains/sandbox22aee81b13674403a5335202df94f7e7.mailgun.org/messages/message_key","key":"message_key"},"campaigns":[],"tags":["my_tag_1","my_tag_2"],"user-variables":{"my_var_1":"Mailgun Variable #1","my-var-2":"awesome"}}}"""

    val payload = CollectorPayload(
      Shared.api,
      Nil,
      ContentType.some,
      body.some,
      Shared.cljSource,
      Shared.context
    )
    val expectedJson =
      """|{
         |  "schema": "iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
         |  "data": {
         |    "schema": "iglu:com.mailgun/message_delivered/jsonschema/1-0-0",
         |    "data": {
         |      "recipient": "alice@example.com",
         |      "deliveryStatus": {
         |        "certificateVerified": true,
         |        "sessionSeconds": 0.4331989288330078,
         |        "description": "",
         |        "mxHost": "smtp-in.example.com",
         |        "tls": true,
         |        "code": 250,
         |        "attemptNo": 1,
         |        "utf8": true,
         |        "message": "OK"
         |      },
         |      "timestamp": "2022-07-15T17:57:13.000Z",
         |      "flags": {
         |        "isRouted": false,
         |        "isAuthenticated": true,
         |        "isSystemTest": false,
         |        "isTestMode": false
         |      },
         |      "tags": [
         |        "my_tag_1",
         |        "my_tag_2"
         |      ],
         |      "signature":"496413b238ab6affce021b850d3fc72c4832fb04f1aed6d4a2fda7d08e6e95a6",
         |      "id": "CPgfbmQMTCKtHW6uIWtuVe",
         |      "recipientDomain": "example.com",
         |      "userVariables": {
         |        "myVar1": "Mailgun Variable #1",
         |        "myVar2": "awesome"
         |      },
         |      "token":"090e1ce3702378c8121f3765a8efe0ffb97c4e2ca2adda6729",
         |      "message": {
         |        "headers": {
         |          "to": "Alice <alice@example.com>",
         |          "messageId": "20130503182626.18666.16540@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org",
         |          "from": "Bob <bob@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org>",
         |          "subject": "Test delivered webhook"
         |        },
         |        "attachments": [],
         |        "size": 111
         |      },
         |      "storage": {
         |        "url": "https://se.api.mailgun.net/v3/domains/sandbox22aee81b13674403a5335202df94f7e7.mailgun.org/messages/message_key",
         |        "key": "message_key"
         |      },
         |      "campaigns": [],
         |      "envelope": {
         |        "transport": "smtp",
         |        "sender": "bob@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org",
         |        "sendingIp": "209.61.154.250",
         |        "targets": "alice@example.com"
         |      },
         |      "logLevel": "info"
         |    }
         |  }
         |}""".stripMargin.replaceAll("[\n\r]", "")

    val expected = NonEmptyList.one(
      RawEvent(
        Shared.api,
        Map("tv" -> "com.mailgun-v1", "e" -> "ue", "p" -> "srv", "ue_pr" -> parse(expectedJson).map(_.noSpaces).right.get).toOpt,
        ContentType.some,
        Shared.cljSource,
        Shared.context
      )
    )
    adapterWithDefaultSchemas.toRawEvents(payload, SpecHelpers.client) must beValid(expected)
  }

  def e2 = {
    val body =
      """{"signature":{"token":"52ff6f6383a394343c4de6e5d4fd870f4ae67b5daeebe3eb86","timestamp":"1659551876","signature":"b2314a28591eccaef5ea88fdf46b99202d8be44e3d1f911abd7e8fe3db726120"},"event-data":{"id":"Ase7i2zsRYeDXztHGENqRA","timestamp":1521243339.873676,"log-level":"info","event":"opened","message":{"headers":{"message-id":"20130503182626.18666.16540@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org"}},"recipient":"alice@example.com","recipient-domain":"example.com","ip":"50.56.129.169","geolocation":{"country":"US","region":"CA","city":"San Francisco"},"client-info":{"client-os":"Linux","device-type":"desktop","client-name":"Chrome","client-type":"browser","user-agent":"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.31 (KHTML, like Gecko) Chrome/26.0.1410.43 Safari/537.31"},"campaigns":[],"tags":["my_tag_1","my_tag_2"],"user-variables":{"my_var_1":"Mailgun Variable #1","my-var-2":"awesome"}}}"""
    val payload = CollectorPayload(
      Shared.api,
      Nil,
      ContentType.some,
      body.some,
      Shared.cljSource,
      Shared.context
    )
    val expectedJson =
      """|{
          |"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
          |"data":{
            |"schema":"iglu:com.mailgun/message_opened/jsonschema/1-0-0",
            |"data":{
            |  "recipient": "alice@example.com",
            |  "ip": "50.56.129.169",
            |  "timestamp": "2022-08-03T18:37:56.000Z",
            |  "tags": [
            |    "my_tag_1",
            |    "my_tag_2"
            |  ],
            |  "clientInfo": {
            |    "deviceType": "desktop",
            |    "userAgent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.31 (KHTML, like Gecko) Chrome/26.0.1410.43 Safari/537.31",
            |    "clientType": "browser",
            |    "clientName": "Chrome",
            |    "clientOs": "Linux"
            |  },
            |  "signature":"b2314a28591eccaef5ea88fdf46b99202d8be44e3d1f911abd7e8fe3db726120",
            |  "geolocation": {
            |    "country": "US",
            |    "region": "CA",
            |    "city": "San Francisco"
            |  },
            |  "id": "Ase7i2zsRYeDXztHGENqRA",
            |  "recipientDomain": "example.com",
            |  "userVariables": {
            |    "myVar1": "Mailgun Variable #1",
            |    "myVar2": "awesome"
            |  },
            |  "token":"52ff6f6383a394343c4de6e5d4fd870f4ae67b5daeebe3eb86",
            |  "message": {
            |    "headers": {
            |      "messageId": "20130503182626.18666.16540@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org"
            |    }
            |  },
            |  "campaigns": [],
            |  "logLevel": "info"
            |}
          |}
        |}""".stripMargin.replaceAll("[\n\r]", "")

    val expected = NonEmptyList.one(
      RawEvent(
        Shared.api,
        Map("tv" -> "com.mailgun-v1", "e" -> "ue", "p" -> "srv", "ue_pr" -> parse(expectedJson).map(_.noSpaces).right.get).toOpt,
        ContentType.some,
        Shared.cljSource,
        Shared.context
      )
    )
    adapterWithDefaultSchemas.toRawEvents(payload, SpecHelpers.client) must beValid(expected)
  }

  def e3 = {
    val body =
      """{"signature":{"token":"a80c92b02bf725bdd9ac58d681ed74a703ad616098b502f1cb","timestamp":"1659551885","signature":"eaf9de9689d7fbc8d39b90a19bb6cb837ec6451f8d1956d6bd9be09255e03af9"},"event-data":{"id":"Ase7i2zsRYeDXztHGENqRA","timestamp":1521243339.873676,"log-level":"info","event":"clicked","message":{"headers":{"message-id":"20130503182626.18666.16540@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org"}},"recipient":"alice@example.com","recipient-domain":"example.com","ip":"50.56.129.169","geolocation":{"country":"US","region":"CA","city":"San Francisco"},"client-info":{"client-os":"Linux","device-type":"desktop","client-name":"Chrome","client-type":"browser","user-agent":"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.31 (KHTML, like Gecko) Chrome/26.0.1410.43 Safari/537.31"},"campaigns":[],"tags":["my_tag_1","my_tag_2"],"user-variables":{"my_var_1":"Mailgun Variable #1","my-var-2":"awesome"}}}"""
    val payload = CollectorPayload(
      Shared.api,
      Nil,
      ContentType.some,
      body.some,
      Shared.cljSource,
      Shared.context
    )
    val expectedJson =
      """|{
          |"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
          |"data":{
            |"schema":"iglu:com.mailgun/message_clicked/jsonschema/1-0-0",
            |"data":{"recipient":"alice@example.com","ip":"50.56.129.169","timestamp":"2022-08-03T18:38:05.000Z","tags":["my_tag_1","my_tag_2"],"clientInfo":{"deviceType":"desktop","userAgent":"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.31 (KHTML, like Gecko) Chrome/26.0.1410.43 Safari/537.31","clientType":"browser","clientName":"Chrome","clientOs":"Linux"},"signature":"eaf9de9689d7fbc8d39b90a19bb6cb837ec6451f8d1956d6bd9be09255e03af9","geolocation":{"country":"US","region":"CA","city":"San Francisco"},"id":"Ase7i2zsRYeDXztHGENqRA","recipientDomain":"example.com","userVariables":{"myVar1":"Mailgun Variable #1","myVar2":"awesome"},"token":"a80c92b02bf725bdd9ac58d681ed74a703ad616098b502f1cb","message":{"headers":{"messageId":"20130503182626.18666.16540@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org"}},"campaigns":[],"logLevel":"info"}
          |}
        |}""".stripMargin.replaceAll("[\n\r]", "")

    val expected = NonEmptyList.one(
      RawEvent(
        Shared.api,
        Map("tv" -> "com.mailgun-v1", "e" -> "ue", "p" -> "srv", "ue_pr" -> parse(expectedJson).map(_.noSpaces).right.get).toOpt,
        ContentType.some,
        Shared.cljSource,
        Shared.context
      )
    )
    adapterWithDefaultSchemas.toRawEvents(payload, SpecHelpers.client) must beValid(expected)
  }

  def e4 = {
    val body =
      """{"signature":{"token":"136f8d92f5955101b0caf54c1c05d62742bae30cb583f4a2a8","timestamp":"1659551891","signature":"ba27e487214505e4da2b9def4d0b83bd99a8bec0cb8b91457a97f2bc3d93eda4"},"event-data":{"id":"Ase7i2zsRYeDXztHGENqRA","timestamp":1521243339.873676,"log-level":"info","event":"unsubscribed","message":{"headers":{"message-id":"20130503182626.18666.16540@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org"}},"recipient":"alice@example.com","recipient-domain":"example.com","ip":"50.56.129.169","geolocation":{"country":"US","region":"CA","city":"San Francisco"},"client-info":{"client-os":"Linux","device-type":"desktop","client-name":"Chrome","client-type":"browser","user-agent":"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.31 (KHTML, like Gecko) Chrome/26.0.1410.43 Safari/537.31"},"campaigns":[],"tags":["my_tag_1","my_tag_2"],"user-variables":{"my_var_1":"Mailgun Variable #1","my-var-2":"awesome"}}}"""
    val payload = CollectorPayload(
      Shared.api,
      Nil,
      ContentType.some,
      body.some,
      Shared.cljSource,
      Shared.context
    )
    val expectedJson =
      """|{
          |"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
          |"data":{
            |"schema":"iglu:com.mailgun/recipient_unsubscribed/jsonschema/1-0-0",
            |"data":{"recipient":"alice@example.com","ip":"50.56.129.169","timestamp":"2022-08-03T18:38:11.000Z","tags":["my_tag_1","my_tag_2"],"clientInfo":{"deviceType":"desktop","userAgent":"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.31 (KHTML, like Gecko) Chrome/26.0.1410.43 Safari/537.31","clientType":"browser","clientName":"Chrome","clientOs":"Linux"},"signature":"ba27e487214505e4da2b9def4d0b83bd99a8bec0cb8b91457a97f2bc3d93eda4","geolocation":{"country":"US","region":"CA","city":"San Francisco"},"id":"Ase7i2zsRYeDXztHGENqRA","recipientDomain":"example.com","userVariables":{"myVar1":"Mailgun Variable #1","myVar2":"awesome"},"token":"136f8d92f5955101b0caf54c1c05d62742bae30cb583f4a2a8","message":{"headers":{"messageId":"20130503182626.18666.16540@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org"}},"campaigns":[],"logLevel":"info"}
          |}
        |}""".stripMargin.replaceAll("[\n\r]", "")

    val expected = NonEmptyList.one(
      RawEvent(
        Shared.api,
        Map("tv" -> "com.mailgun-v1", "e" -> "ue", "p" -> "srv", "ue_pr" -> expectedJson).toOpt,
        ContentType.some,
        Shared.cljSource,
        Shared.context
      )
    )
    adapterWithDefaultSchemas.toRawEvents(payload, SpecHelpers.client) must beValid(expected)
  }

  def e5 = {
    val body =
      """{"signature":{"token":"090e1ce3702378c8121f3765a8efe0ffb97c4e2ca2adda6729","timestamp":"1657907833","signature":"496413b238ab6affce021b850d3fc72c4832fb04f1aed6d4a2fda7d08e6e95a6"},"event-data":{"id":"CPgfbmQMTCKtHW6uIWtuVe","timestamp":1521472262.908181,"log-level":"info","event":"delivered","delivery-status":{"tls":true,"mx-host":"smtp-in.example.com","code":250,"description":"","session-seconds":0.4331989288330078,"utf8":true,"attempt-no":1,"message":"OK","certificate-verified":true},"flags":{"is-routed":false,"is-authenticated":true,"is-system-test":false,"is-test-mode":false},"envelope":{"transport":"smtp","sender":"bob@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org","sending-ip":"209.61.154.250","targets":"alice@example.com"},"message":{"headers":{"to":"Alice <alice@example.com>","message-id":"20130503182626.18666.16540@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org","from":"Bob <bob@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org>","subject":"Test delivered webhook"},"attachments":[],"size":111},"recipient":"alice@example.com","recipient-domain":"example.com","storage":{"url":"https://se.api.mailgun.net/v3/domains/sandbox22aee81b13674403a5335202df94f7e7.mailgun.org/messages/message_key","key":"message_key"},"campaigns":[],"tags":["my_tag_1","my_tag_2"],"user-variables":{"my_var_1":"Mailgun Variable #1","my-var-2":"awesome"}}}"""
    val payload = CollectorPayload(
      Shared.api,
      Nil,
      Some("application/json"),
      body.some,
      Shared.cljSource,
      Shared.context
    )
    val expectedJson =
      """|{
         |  "schema": "iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
         |  "data": {
         |    "schema": "iglu:com.mailgun/message_delivered/jsonschema/1-0-0",
         |    "data": {
         |      "recipient": "alice@example.com",
         |      "deliveryStatus": {
         |        "certificateVerified": true,
         |        "sessionSeconds": 0.4331989288330078,
         |        "description": "",
         |        "mxHost": "smtp-in.example.com",
         |        "tls": true,
         |        "code": 250,
         |        "attemptNo": 1,
         |        "utf8": true,
         |        "message": "OK"
         |      },
         |      "timestamp": "2022-07-15T17:57:13.000Z",
         |      "flags": {
         |        "isRouted": false,
         |        "isAuthenticated": true,
         |        "isSystemTest": false,
         |        "isTestMode": false
         |      },
         |      "tags": [
         |        "my_tag_1",
         |        "my_tag_2"
         |      ],
         |      "signature":"496413b238ab6affce021b850d3fc72c4832fb04f1aed6d4a2fda7d08e6e95a6",
         |      "id": "CPgfbmQMTCKtHW6uIWtuVe",
         |      "recipientDomain": "example.com",
         |      "userVariables": {
         |        "myVar1": "Mailgun Variable #1",
         |        "myVar2": "awesome"
         |      },
         |      "token":"090e1ce3702378c8121f3765a8efe0ffb97c4e2ca2adda6729",
         |      "message": {
         |        "headers": {
         |          "to": "Alice <alice@example.com>",
         |          "messageId": "20130503182626.18666.16540@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org",
         |          "from": "Bob <bob@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org>",
         |          "subject": "Test delivered webhook"
         |        },
         |        "attachments": [],
         |        "size": 111
         |      },
         |      "storage": {
         |        "url": "https://se.api.mailgun.net/v3/domains/sandbox22aee81b13674403a5335202df94f7e7.mailgun.org/messages/message_key",
         |        "key": "message_key"
         |      },
         |      "campaigns": [],
         |      "envelope": {
         |        "transport": "smtp",
         |        "sender": "bob@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org",
         |        "sendingIp": "209.61.154.250",
         |        "targets": "alice@example.com"
         |      },
         |      "logLevel": "info"
         |    }
         |  }
         |}""".stripMargin.replaceAll("[\n\r]", "")
    val expected = NonEmptyList.one(
      RawEvent(
        Shared.api,
        Map("tv" -> "com.mailgun-v1", "e" -> "ue", "p" -> "srv", "ue_pr" -> parse(expectedJson).map(_.noSpaces).right.get).toOpt,
        Some("application/json"),
        Shared.cljSource,
        Shared.context
      )
    )
    adapterWithDefaultSchemas.toRawEvents(payload, SpecHelpers.client) must beValid(expected)
  }

  def e6 = {
    val payload =
      CollectorPayload(Shared.api, Nil, ContentType.some, None, Shared.cljSource, Shared.context)
    adapterWithDefaultSchemas.toRawEvents(payload, SpecHelpers.client) must beInvalid(
      NonEmptyList.one(
        FailureDetails.AdapterFailure
          .InputData("body", None, "empty body: no events to process")
      )
    )
  }

  def e7 = {
    val body = "body"
    val payload =
      CollectorPayload(Shared.api, Nil, None, body.some, Shared.cljSource, Shared.context)
    adapterWithDefaultSchemas.toRawEvents(payload, SpecHelpers.client) must beInvalid(
      NonEmptyList.one(
        FailureDetails.AdapterFailure.InputData(
          "contentType",
          None,
          "no content type: expected application/json"
        )
      )
    )
  }

  def e8 = {
    val body = "body"
    val ct = "multipart/form-data"
    val payload =
      CollectorPayload(Shared.api, Nil, ct.some, body.some, Shared.cljSource, Shared.context)
    adapterWithDefaultSchemas.toRawEvents(payload, SpecHelpers.client) must beInvalid(
      NonEmptyList.one(
        FailureDetails.AdapterFailure.InputData(
          "contentType",
          ct.some,
          "expected application/json"
        )
      )
    )
  }

  def e9 = {
    val body = ""
    val payload = CollectorPayload(
      Shared.api,
      Nil,
      ContentType.some,
      body.some,
      Shared.cljSource,
      Shared.context
    )
    val expected =
      NonEmptyList.one(
        FailureDetails.AdapterFailure
          .InputData("body", None, "empty body: no events to process")
      )
    adapterWithDefaultSchemas.toRawEvents(payload, SpecHelpers.client) must beInvalid(expected)
  }

  def e10 = {
    val body = "{}"
    val payload = CollectorPayload(
      Shared.api,
      Nil,
      ContentType.some,
      body.some,
      Shared.cljSource,
      Shared.context
    )
    val expected = NonEmptyList.one(
      FailureDetails.AdapterFailure.InputData(
        "body",
        body.some,
        "no `event` parameter provided: cannot determine event type"
      )
    )
    adapterWithDefaultSchemas.toRawEvents(payload, SpecHelpers.client) must beInvalid(expected)
  }

  def e11 = {
    val body =
      """{"signature":{"token":"090e1ce3702378c8121f3765a8efe0ffb97c4e2ca2adda6729","timestamp":"1657907833","signature":"496413b238ab6affce021b850d3fc72c4832fb04f1aed6d4a2fda7d08e6e95a6"},"event-data":{"id":"CPgfbmQMTCKtHW6uIWtuVe","timestamp":1521472262.908181,"log-level":"info","event":"released","delivery-status":{"tls":true,"mx-host":"smtp-in.example.com","code":250,"description":"","session-seconds":0.4331989288330078,"utf8":true,"attempt-no":1,"message":"OK","certificate-verified":true},"flags":{"is-routed":false,"is-authenticated":true,"is-system-test":false,"is-test-mode":false},"envelope":{"transport":"smtp","sender":"bob@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org","sending-ip":"209.61.154.250","targets":"alice@example.com"},"message":{"headers":{"to":"Alice <alice@example.com>","message-id":"20130503182626.18666.16540@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org","from":"Bob <bob@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org>","subject":"Test delivered webhook"},"attachments":[],"size":111},"recipient":"alice@example.com","recipient-domain":"example.com","storage":{"url":"https://se.api.mailgun.net/v3/domains/sandbox22aee81b13674403a5335202df94f7e7.mailgun.org/messages/message_key","key":"message_key"},"campaigns":[],"tags":["my_tag_1","my_tag_2"],"user-variables":{"my_var_1":"Mailgun Variable #1","my-var-2":"awesome"}}}"""
    val payload = CollectorPayload(
      Shared.api,
      Nil,
      ContentType.some,
      body.some,
      Shared.cljSource,
      Shared.context
    )
    val expected = NonEmptyList.one(
      FailureDetails.AdapterFailure.SchemaMapping(
        "released".some,
        adapterWithDefaultSchemas.EventSchemaMap,
        "no schema associated with the provided type parameter"
      )
    )
    adapterWithDefaultSchemas.toRawEvents(payload, SpecHelpers.client) must beInvalid(expected)
  }

  def e12 = {
    val body =
      """{"signature":{"token":"090e1ce3702378c8121f3765a8efe0ffb97c4e2ca2adda6729","signature":"496413b238ab6affce021b850d3fc72c4832fb04f1aed6d4a2fda7d08e6e95a6"},"event-data":{"id":"CPgfbmQMTCKtHW6uIWtuVe","timestamp":1521472262.908181,"log-level":"info","event":"delivered","delivery-status":{"tls":true,"mx-host":"smtp-in.example.com","code":250,"description":"","session-seconds":0.4331989288330078,"utf8":true,"attempt-no":1,"message":"OK","certificate-verified":true},"flags":{"is-routed":false,"is-authenticated":true,"is-system-test":false,"is-test-mode":false},"envelope":{"transport":"smtp","sender":"bob@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org","sending-ip":"209.61.154.250","targets":"alice@example.com"},"message":{"headers":{"to":"Alice <alice@example.com>","message-id":"20130503182626.18666.16540@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org","from":"Bob <bob@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org>","subject":"Test delivered webhook"},"attachments":[],"size":111},"recipient":"alice@example.com","recipient-domain":"example.com","storage":{"url":"https://se.api.mailgun.net/v3/domains/sandbox22aee81b13674403a5335202df94f7e7.mailgun.org/messages/message_key","key":"message_key"},"campaigns":[],"tags":["my_tag_1","my_tag_2"],"user-variables":{"my_var_1":"Mailgun Variable #1","my-var-2":"awesome"}}}"""
    val payload = CollectorPayload(
      Shared.api,
      Nil,
      ContentType.some,
      body.some,
      Shared.cljSource,
      Shared.context
    )
    val expected =
      NonEmptyList.one(
        FailureDetails.AdapterFailure
          .InputData("timestamp", None, "missing 'timestamp'")
      )
    adapterWithDefaultSchemas.toRawEvents(payload, SpecHelpers.client) must beInvalid(expected)
  }

  def e13 = {
    val body =
      """{"signature":{"timestamp":"1657907833","signature":"496413b238ab6affce021b850d3fc72c4832fb04f1aed6d4a2fda7d08e6e95a6"},"event-data":{"id":"CPgfbmQMTCKtHW6uIWtuVe","timestamp":1521472262.908181,"log-level":"info","event":"delivered","delivery-status":{"tls":true,"mx-host":"smtp-in.example.com","code":250,"description":"","session-seconds":0.4331989288330078,"utf8":true,"attempt-no":1,"message":"OK","certificate-verified":true},"flags":{"is-routed":false,"is-authenticated":true,"is-system-test":false,"is-test-mode":false},"envelope":{"transport":"smtp","sender":"bob@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org","sending-ip":"209.61.154.250","targets":"alice@example.com"},"message":{"headers":{"to":"Alice <alice@example.com>","message-id":"20130503182626.18666.16540@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org","from":"Bob <bob@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org>","subject":"Test delivered webhook"},"attachments":[],"size":111},"recipient":"alice@example.com","recipient-domain":"example.com","storage":{"url":"https://se.api.mailgun.net/v3/domains/sandbox22aee81b13674403a5335202df94f7e7.mailgun.org/messages/message_key","key":"message_key"},"campaigns":[],"tags":["my_tag_1","my_tag_2"],"user-variables":{"my_var_1":"Mailgun Variable #1","my-var-2":"awesome"}}}"""
    val payload = CollectorPayload(
      Shared.api,
      Nil,
      ContentType.some,
      body.some,
      Shared.cljSource,
      Shared.context
    )
    val expected = NonEmptyList.one(
      FailureDetails.AdapterFailure.InputData("token", None, "missing 'token'")
    )
    adapterWithDefaultSchemas.toRawEvents(payload, SpecHelpers.client) must beInvalid(expected)
  }

  def e14 = {
    val body =
      """{"signature":{"token":"090e1ce3702378c8121f3765a8efe0ffb97c4e2ca2adda6729","timestamp":"1657907833"},"event-data":{"id":"CPgfbmQMTCKtHW6uIWtuVe","timestamp":1521472262.908181,"log-level":"info","event":"delivered","delivery-status":{"tls":true,"mx-host":"smtp-in.example.com","code":250,"description":"","session-seconds":0.4331989288330078,"utf8":true,"attempt-no":1,"message":"OK","certificate-verified":true},"flags":{"is-routed":false,"is-authenticated":true,"is-system-test":false,"is-test-mode":false},"envelope":{"transport":"smtp","sender":"bob@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org","sending-ip":"209.61.154.250","targets":"alice@example.com"},"message":{"headers":{"to":"Alice <alice@example.com>","message-id":"20130503182626.18666.16540@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org","from":"Bob <bob@sandbox22aee81b13674403a5335202df94f7e7.mailgun.org>","subject":"Test delivered webhook"},"attachments":[],"size":111},"recipient":"alice@example.com","recipient-domain":"example.com","storage":{"url":"https://se.api.mailgun.net/v3/domains/sandbox22aee81b13674403a5335202df94f7e7.mailgun.org/messages/message_key","key":"message_key"},"campaigns":[],"tags":["my_tag_1","my_tag_2"],"user-variables":{"my_var_1":"Mailgun Variable #1","my-var-2":"awesome"}}}"""
    val payload = CollectorPayload(
      Shared.api,
      Nil,
      ContentType.some,
      body.some,
      Shared.cljSource,
      Shared.context
    )
    val expected =
      NonEmptyList.one(
        FailureDetails.AdapterFailure
          .InputData("signature", None, "missing 'signature'")
      )
    adapterWithDefaultSchemas.toRawEvents(payload, SpecHelpers.client) must beInvalid(expected)
  }
}
