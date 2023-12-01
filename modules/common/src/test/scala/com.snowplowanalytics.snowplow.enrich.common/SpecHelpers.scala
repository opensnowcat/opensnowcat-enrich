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

import cats.Id
import cats.implicits._

import com.snowplowanalytics.iglu.client.IgluCirceClient
import com.snowplowanalytics.iglu.core.SelfDescribingData
import com.snowplowanalytics.iglu.core.circe.implicits._

import com.snowplowanalytics.lrumap.CreateLruMap._

import io.circe.Json
import io.circe.literal._

import org.apache.http.NameValuePair
import org.apache.http.message.BasicNameValuePair

import com.snowplowanalytics.snowplow.enrich.common.adapters._
import com.snowplowanalytics.snowplow.enrich.common.utils.JsonUtils

object SpecHelpers {

  // Standard Iglu configuration
  private val igluConfig = json"""{
    "schema": "iglu:com.snowplowanalytics.iglu/resolver-config/jsonschema/1-0-0",
    "data": {
      "cacheSize": 500,
      "repositories": [
        {
          "name": "Iglu Central",
          "priority": 0,
          "vendorPrefixes": [ "com.snowplowanalytics" ],
          "connection": {
            "http": {
              "uri": "http://iglucentral.com"
            }
          }
        },
        {
          "name": "Embedded src/test/resources",
          "priority": 100,
          "vendorPrefixes": [ "com.snowplowanalytics" ],
          "connection": {
            "embedded": {
              "path": "/iglu-schemas"
            }
          }
        }
      ]
    }
  }"""

  /** Builds an Iglu client from the above Iglu configuration. */
  val client: IgluCirceClient[Id] = IgluCirceClient
    .parseDefault[Id](igluConfig)
    .value
    .getOrElse(throw new RuntimeException("invalid resolver configuration"))

  private type NvPair = (String, String)

  /**
   * Converts an NvPair into a
   * BasicNameValuePair
   *
   * @param pair The Tuple2[String, String] name-value
   * pair to convert
   * @return the basic name value pair
   */
  private def toNvPair(pair: NvPair): BasicNameValuePair =
    new BasicNameValuePair(pair._1, pair._2)

  /** Converts the supplied NvPairs into a NameValueNel */
  def toNameValuePairs(pairs: NvPair*): List[NameValuePair] =
    List(pairs.map(toNvPair): _*)

  /**
   * Builds a self-describing JSON by
   * wrapping the supplied JSON with
   * schema and data properties
   *
   * @param json The JSON to use as the request body
   * @param schema The name of the schema to insert
   * @return a self-describing JSON
   */
  def toSelfDescJson(json: String, schema: String): String =
    s"""{"schema":"iglu:com.snowplowanalytics.snowplow/${schema}/jsonschema/1-0-0","data":${json}}"""

  /** Parse a string containing a SDJ as [[SelfDescribingData]] */
  def jsonStringToSDJ(rawJson: String): Either[String, SelfDescribingData[Json]] =
    JsonUtils
      .extractJson(rawJson)
      .leftMap(err => s"Can't parse [$rawJson] as Json, error: [$err]")
      .flatMap(SelfDescribingData.parse[Json])
      .leftMap(err => s"Can't parse Json [$rawJson] as as SelfDescribingData, error: [$err]")

  implicit class MapOps[A, B](underlying: Map[A, B]) {
    def toOpt: Map[A, Option[B]] = underlying.map { case (a, b) => (a, Option(b)) }
  }

  val callrailSchemas = CallrailSchemas(
    call_complete = "iglu:com.callrail/call_complete/jsonschema/1-0-2"
  )

  val cloudfrontAccessLogSchemas = CloudfrontAccessLogSchemas(
    with_12_fields = "iglu:com.amazon.aws.cloudfront/wd_access_log/jsonschema/1-0-0",
    with_15_fields = "iglu:com.amazon.aws.cloudfront/wd_access_log/jsonschema/1-0-1",
    with_18_fields = "iglu:com.amazon.aws.cloudfront/wd_access_log/jsonschema/1-0-2",
    with_19_fields = "iglu:com.amazon.aws.cloudfront/wd_access_log/jsonschema/1-0-3",
    with_23_fields = "iglu:com.amazon.aws.cloudfront/wd_access_log/jsonschema/1-0-4",
    with_24_fields = "iglu:com.amazon.aws.cloudfront/wd_access_log/jsonschema/1-0-5",
    with_26_fields = "iglu:com.amazon.aws.cloudfront/wd_access_log/jsonschema/1-0-6"
  )

  val sendgridSchemas = SendgridSchemas(
    processed = "iglu:com.sendgrid/processed/jsonschema/2-0-0",
    dropped = "iglu:com.sendgrid/dropped/jsonschema/2-0-0",
    delivered = "iglu:com.sendgrid/delivered/jsonschema/2-0-0",
    bounce = "iglu:com.sendgrid/bounce/jsonschema/2-0-0",
    deferred = "iglu:com.sendgrid/deferred/jsonschema/2-0-0",
    open = "iglu:com.sendgrid/open/jsonschema/2-0-0",
    click = "iglu:com.sendgrid/click/jsonschema/2-0-0",
    unsubscribe = "iglu:com.sendgrid/unsubscribe/jsonschema/2-0-0",
    group_unsubscribe = "iglu:com.sendgrid/group_unsubscribe/jsonschema/2-0-0",
    group_resubscribe = "iglu:com.sendgrid/group_resubscribe/jsonschema/2-0-0",
    spamreport = "iglu:com.sendgrid/spamreport/jsonschema/2-0-0"
  )

  val googleAnalyticsSchemas = GoogleAnalyticsSchemas(
    page_view = "iglu:com.google.analytics.measurement-protocol/page_view/jsonschema/1-0-0",
    screen_view = "iglu:com.google.analytics.measurement-protocol/screen_view/jsonschema/1-0-0",
    event = "iglu:com.google.analytics.measurement-protocol/event/jsonschema/1-0-0",
    transaction = "iglu:com.google.analytics.measurement-protocol/transaction/jsonschema/1-0-0",
    item = "iglu:com.google.analytics.measurement-protocol/item/jsonschema/1-0-0",
    social = "iglu:com.google.analytics.measurement-protocol/social/jsonschema/1-0-0",
    exception = "iglu:com.google.analytics.measurement-protocol/exception/jsonschema/1-0-0",
    timing = "iglu:com.google.analytics.measurement-protocol/timing/jsonschema/1-0-0",
    undocumented = "iglu:com.google.analytics/undocumented/jsonschema/1-0-0",
    `private` = "iglu:com.google.analytics/private/jsonschema/1-0-0",
    general = "iglu:com.google.analytics.measurement-protocol/general/jsonschema/1-0-0",
    user = "iglu:com.google.analytics.measurement-protocol/user/jsonschema/1-0-0",
    session = "iglu:com.google.analytics.measurement-protocol/session/jsonschema/1-0-0",
    traffic_source = "iglu:com.google.analytics.measurement-protocol/traffic_source/jsonschema/1-0-0",
    system_info = "iglu:com.google.analytics.measurement-protocol/system_info/jsonschema/1-0-0",
    link = "iglu:com.google.analytics.measurement-protocol/link/jsonschema/1-0-0",
    app = "iglu:com.google.analytics.measurement-protocol/app/jsonschema/1-0-0",
    product_action = "iglu:com.google.analytics.measurement-protocol/product_action/jsonschema/1-0-0",
    content_experiment = "iglu:com.google.analytics.measurement-protocol/content_experiment/jsonschema/1-0-0",
    hit = "iglu:com.google.analytics.measurement-protocol/hit/jsonschema/1-0-0",
    promotion_action = "iglu:com.google.analytics.measurement-protocol/promotion_action/jsonschema/1-0-0",
    product = "iglu:com.google.analytics.measurement-protocol/product/jsonschema/1-0-0",
    product_custom_dimension = "iglu:com.google.analytics.measurement-protocol/product_custom_dimension/jsonschema/1-0-0",
    product_custom_metric = "iglu:com.google.analytics.measurement-protocol/product_custom_metric/jsonschema/1-0-0",
    product_impression_list = "iglu:com.google.analytics.measurement-protocol/product_impression_list/jsonschema/1-0-0",
    product_impression = "iglu:com.google.analytics.measurement-protocol/product_impression/jsonschema/1-0-0",
    product_impression_custom_dimension =
      "iglu:com.google.analytics.measurement-protocol/product_impression_custom_dimension/jsonschema/1-0-0",
    product_impression_custom_metric = "iglu:com.google.analytics.measurement-protocol/product_impression_custom_metric/jsonschema/1-0-0",
    promotion = "iglu:com.google.analytics.measurement-protocol/promotion/jsonschema/1-0-0",
    custom_dimension = "iglu:com.google.analytics.measurement-protocol/custom_dimension/jsonschema/1-0-0",
    custom_metric = "iglu:com.google.analytics.measurement-protocol/custom_metric/jsonschema/1-0-0",
    content_group = "iglu:com.google.analytics.measurement-protocol/content_group/jsonschema/1-0-0"
  )

  val hubspotSchemas = HubspotSchemas(
    contact_creation = "iglu:com.hubspot/contact_creation/jsonschema/1-0-0",
    contact_deletion = "iglu:com.hubspot/contact_deletion/jsonschema/1-0-0",
    contact_change = "iglu:com.hubspot/contact_change/jsonschema/1-0-0",
    company_creation = "iglu:com.hubspot/company_creation/jsonschema/1-0-0",
    company_deletion = "iglu:com.hubspot/company_deletion/jsonschema/1-0-0",
    company_change = "iglu:com.hubspot/company_change/jsonschema/1-0-0",
    deal_creation = "iglu:com.hubspot/deal_creation/jsonschema/1-0-0",
    deal_deletion = "iglu:com.hubspot/deal_deletion/jsonschema/1-0-0",
    deal_change = "iglu:com.hubspot/deal_change/jsonschema/1-0-0"
  )

  val mailchimpSchemas = MailchimpSchemas(
    subscribe = "iglu:com.mailchimp/subscribe/jsonschema/1-0-0",
    unsubscribe = "iglu:com.mailchimp/unsubscribe/jsonschema/1-0-0",
    campaign_sending_status = "iglu:com.mailchimp/campaign_sending_status/jsonschema/1-0-0",
    cleaned_email = "iglu:com.mailchimp/cleaned_email/jsonschema/1-0-0",
    email_address_change = "iglu:com.mailchimp/email_address_change/jsonschema/1-0-0",
    profile_update = "iglu:com.mailchimp/profile_update/jsonschema/1-0-0"
  )

  val mailgunSchemas = MailgunSchemas(
    message_bounced = "iglu:com.mailgun/message_bounced/jsonschema/1-0-0",
    message_clicked = "iglu:com.mailgun/message_clicked/jsonschema/1-0-0",
    message_complained = "iglu:com.mailgun/message_complained/jsonschema/1-0-0",
    message_delivered = "iglu:com.mailgun/message_delivered/jsonschema/1-0-0",
    message_dropped = "iglu:com.mailgun/message_dropped/jsonschema/1-0-0",
    message_opened = "iglu:com.mailgun/message_opened/jsonschema/1-0-0",
    recipient_unsubscribed = "iglu:com.mailgun/recipient_unsubscribed/jsonschema/1-0-0"
  )

  val mandrillSchemas = MandrillSchemas(
    message_bounced = "iglu:com.mandrill/message_bounced/jsonschema/1-0-1",
    message_clicked = "iglu:com.mandrill/message_clicked/jsonschema/1-0-1",
    message_delayed = "iglu:com.mandrill/message_delayed/jsonschema/1-0-1",
    message_marked_as_spam = "iglu:com.mandrill/message_marked_as_spam/jsonschema/1-0-1",
    message_opened = "iglu:com.mandrill/message_opened/jsonschema/1-0-1",
    message_rejected = "iglu:com.mandrill/message_rejected/jsonschema/1-0-0",
    message_sent = "iglu:com.mandrill/message_sent/jsonschema/1-0-0",
    message_soft_bounced = "iglu:com.mandrill/message_soft_bounced/jsonschema/1-0-1",
    recipient_unsubscribed = "iglu:com.mandrill/recipient_unsubscribed/jsonschema/1-0-1"
  )

  val marketoSchemas = MarketoSchemas(
    event = "iglu:com.marketo/event/jsonschema/2-0-0"
  )

  val olarkSchemas = OlarkSchemas(
    transcript = "iglu:com.olark/transcript/jsonschema/1-0-0",
    offline_message = "iglu:com.olark/offline_message/jsonschema/1-0-0"
  )

  val pagerdutySchemas = PagerdutySchemas(
    incident = "iglu:com.pagerduty/incident/jsonschema/1-0-0"
  )

  val pingdomSchemas = PingdomSchemas(
    incident_assign = "iglu:com.pingdom/incident_assign/jsonschema/1-0-0",
    incident_notify_user = "iglu:com.pingdom/incident_notify_user/jsonschema/1-0-0",
    incident_notify_of_close = "iglu:com.pingdom/incident_notify_of_close/jsonschema/1-0-0"
  )

  val statusGatorSchemas = StatusGatorSchemas(
    status_change = "iglu:com.statusgator/status_change/jsonschema/1-0-0"
  )

  val unbounceSchemas = UnbounceSchemas(
    form_post = "iglu:com.unbounce/form_post/jsonschema/1-0-0"
  )

  val urbanAirshipSchemas = UrbanAirshipSchemas(
    close = "iglu:com.urbanairship.connect/CLOSE/jsonschema/1-0-0",
    custom = "iglu:com.urbanairship.connect/CUSTOM/jsonschema/1-0-0",
    first_open = "iglu:com.urbanairship.connect/FIRST_OPEN/jsonschema/1-0-0",
    in_app_message_display = "iglu:com.urbanairship.connect/IN_APP_MESSAGE_DISPLAY/jsonschema/1-0-0",
    in_app_message_expiration = "iglu:com.urbanairship.connect/IN_APP_MESSAGE_EXPIRATION/jsonschema/1-0-0",
    in_app_message_resolution = "iglu:com.urbanairship.connect/IN_APP_MESSAGE_RESOLUTION/jsonschema/1-0-0",
    location = "iglu:com.urbanairship.connect/LOCATION/jsonschema/1-0-0",
    open = "iglu:com.urbanairship.connect/OPEN/jsonschema/1-0-0",
    push_body = "iglu:com.urbanairship.connect/PUSH_BODY/jsonschema/1-0-0",
    region = "iglu:com.urbanairship.connect/REGION/jsonschema/1-0-0",
    rich_delete = "iglu:com.urbanairship.connect/RICH_DELETE/jsonschema/1-0-0",
    rich_delivery = "iglu:com.urbanairship.connect/RICH_DELIVERY/jsonschema/1-0-0",
    rich_head = "iglu:com.urbanairship.connect/RICH_HEAD/jsonschema/1-0-0",
    send = "iglu:com.urbanairship.connect/SEND/jsonschema/1-0-0",
    tag_change = "iglu:com.urbanairship.connect/TAG_CHANGE/jsonschema/1-0-0",
    uninstall = "iglu:com.urbanairship.connect/UNINSTALL/jsonschema/1-0-0"
  )

  val veroSchemas = VeroSchemas(
    bounced = "iglu:com.getvero/bounced/jsonschema/1-0-0",
    clicked = "iglu:com.getvero/clicked/jsonschema/1-0-0",
    delivered = "iglu:com.getvero/delivered/jsonschema/1-0-0",
    opened = "iglu:com.getvero/opened/jsonschema/1-0-0",
    sent = "iglu:com.getvero/sent/jsonschema/1-0-0",
    unsubscribed = "iglu:com.getvero/unsubscribed/jsonschema/1-0-0",
    created = "iglu:com.getvero/created/jsonschema/1-0-0",
    updated = "iglu:com.getvero/updated/jsonschema/1-0-0"
  )

  val adaptersSchemas = AdaptersSchemas(
    callrail = callrailSchemas,
    cloudfrontAccessLog = cloudfrontAccessLogSchemas,
    googleAnalytics = googleAnalyticsSchemas,
    hubspot = hubspotSchemas,
    mailchimp = mailchimpSchemas,
    mailgun = mailgunSchemas,
    mandrill = mandrillSchemas,
    marketo = marketoSchemas,
    olark = olarkSchemas,
    pagerduty = pagerdutySchemas,
    pingdom = pingdomSchemas,
    sendgrid = sendgridSchemas,
    statusgator = statusGatorSchemas,
    unbounce = unbounceSchemas,
    urbanAirship = urbanAirshipSchemas,
    vero = veroSchemas
  )
}
