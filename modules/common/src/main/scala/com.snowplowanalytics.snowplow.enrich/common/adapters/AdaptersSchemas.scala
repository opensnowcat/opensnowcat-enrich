/*
 * Copyright (c) 2018-2023 Snowplow Analytics Ltd. All rights reserved.
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

package com.snowplowanalytics.snowplow.enrich.common.adapters

import com.snowplowanalytics.iglu.core.SchemaKey

case class AdaptersSchemas(
  callrail: CallrailSchemas,
  cloudfrontAccessLog: CloudfrontAccessLogSchemas,
  googleAnalytics: GoogleAnalyticsSchemas,
  hubspot: HubspotSchemas,
  mailchimp: MailchimpSchemas,
  mailgun: MailgunSchemas,
  mandrill: MandrillSchemas,
  marketo: MarketoSchemas,
  olark: OlarkSchemas,
  pagerduty: PagerdutySchemas,
  pingdom: PingdomSchemas,
  sendgrid: SendgridSchemas,
  statusgator: StatusGatorSchemas,
  unbounce: UnbounceSchemas,
  urbanAirship: UrbanAirshipSchemas,
  vero: VeroSchemas
)

case class CallrailSchemas(call_complete: String) {
  val callCompleteSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(call_complete, "call complete", "Callrail")
}

case class CloudfrontAccessLogSchemas(
  with_12_fields: String,
  with_15_fields: String,
  with_18_fields: String,
  with_19_fields: String,
  with_23_fields: String,
  with_24_fields: String,
  with_26_fields: String
) {
  val with12FieldsSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(with_12_fields, "with 12 fields", "Cloudfront")
  val with15FieldsSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(with_15_fields, "with 15 fields", "Cloudfront")
  val with18FieldsSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(with_18_fields, "with 18 fields", "Cloudfront")
  val with19FieldsSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(with_19_fields, "with 19 fields", "Cloudfront")
  val with23FieldsSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(with_23_fields, "with 23 fields", "Cloudfront")
  val with24FieldsSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(with_24_fields, "with 24 fields", "Cloudfront")
  val with26FieldsSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(with_26_fields, "with 26 fields", "Cloudfront")
}

case class HubspotSchemas(
  contact_creation: String,
  contact_deletion: String,
  contact_change: String,
  company_creation: String,
  company_deletion: String,
  company_change: String,
  deal_creation: String,
  deal_deletion: String,
  deal_change: String
) {

  val contactCreationSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(contact_creation, "contact_creation", "HubSpot")
  val contactDeletionSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(contact_deletion, "contact_deletion", "HubSpot")
  val contactChangeSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(contact_change, "contact_change", "HubSpot")
  val companyCreationSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(company_creation, "company_creation", "HubSpot")
  val companyDeletionSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(company_deletion, "company_deletion", "HubSpot")
  val companyChangeSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(company_change, "company_change", "HubSpot")
  val dealCreationSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(deal_creation, "deal_creation", "HubSpot")
  val dealDeletionSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(deal_deletion, "deal_deletion", "HubSpot")
  val dealChangeSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(deal_change, "deal_change", "HubSpot")
}

case class GoogleAnalyticsSchemas(
  page_view: String,
  screen_view: String,
  event: String,
  transaction: String,
  item: String,
  social: String,
  exception: String,
  timing: String,
  undocumented: String,
  `private`: String,
  general: String,
  user: String,
  session: String,
  traffic_source: String,
  system_info: String,
  link: String,
  app: String,
  product_action: String,
  content_experiment: String,
  hit: String,
  promotion_action: String,
  product: String,
  product_custom_dimension: String,
  product_custom_metric: String,
  product_impression_list: String,
  product_impression: String,
  product_impression_custom_dimension: String,
  product_impression_custom_metric: String,
  promotion: String,
  custom_dimension: String,
  custom_metric: String,
  content_group: String
) {

  val pageViewSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(page_view, "page_view", "Google Analytics")
  val screenViewSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(screen_view, "screen_view", "Google Analytics")
  val eventSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(event, "event", "Google Analytics")
  val transactionSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(transaction, "transaction", "Google Analytics")
  val itemSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(item, "item", "Google Analytics")
  val socialSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(social, "social", "Google Analytics")
  val exceptionSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(exception, "exception", "Google Analytics")
  val timingSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(timing, "timing", "Google Analytics")
  val undocumentedSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(undocumented, "undocumented", "Google Analytics")
  val privateSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(`private`, "private", "Google Analytics")
  val generalSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(general, "general", "Google Analytics")
  val userSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(user, "user", "Google Analytics")
  val sessionSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(session, "session", "Google Analytics")
  val trafficSourceSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(traffic_source, "traffic_source", "Google Analytics")
  val systemInfoSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(system_info, "system_info", "Google Analytics")
  val linkSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(link, "link", "Google Analytics")
  val appSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(app, "app", "Google Analytics")
  val productActionSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(product_action, "product_action", "Google Analytics")
  val contentExperimentSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(content_experiment, "content_experiment", "Google Analytics")
  val hitSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(hit, "hit", "Google Analytics")
  val promotionActionSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(promotion_action, "promotion_action", "Google Analytics")
  val productSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(product, "product", "Google Analytics")
  val productCustomDimensionSchemaKey: SchemaKey =
    AdapterConfigHelper.toSchemaKey(product_custom_dimension, "product_custom_dimension", "Google Analytics")
  val productCustomMetricSchemaKey: SchemaKey =
    AdapterConfigHelper.toSchemaKey(product_custom_metric, "product_custom_metric", "Google Analytics")
  val productImpressionListSchemaKey: SchemaKey =
    AdapterConfigHelper.toSchemaKey(product_impression_list, "product_impression_list", "Google Analytics")
  val productImpressionSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(product_impression, "product_impression", "Google Analytics")
  val productImpressionCustomDimensionSchemaKey: SchemaKey =
    AdapterConfigHelper.toSchemaKey(product_impression_custom_dimension, "product_impression_custom_dimension", "Google Analytics")
  val productImpressionCustomMetricSchemaKey: SchemaKey =
    AdapterConfigHelper.toSchemaKey(product_impression_custom_metric, "product_impression_custom_metric", "Google Analytics")
  val promotionSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(promotion, "promotion", "Google Analytics")
  val customDimensionSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(custom_dimension, "custom_dimension", "Google Analytics")
  val customMetricSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(custom_metric, "custom_metric", "Google Analytics")
  val contentGroupSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(content_group, "content_group", "Google Analytics")
}

case class MailchimpSchemas(
  subscribe: String,
  unsubscribe: String,
  campaign_sending_status: String,
  cleaned_email: String,
  email_address_change: String,
  profile_update: String
) {
  val subscribeSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(subscribe, "subscribe", "Mailchimp")
  val unsubscribeSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(unsubscribe, "unsubscribe", "Mailchimp")
  val campaignSendingStatusSchemaKey: SchemaKey =
    AdapterConfigHelper.toSchemaKey(campaign_sending_status, "campaign_sending_status", "Mailchimp")
  val cleanedEmailSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(cleaned_email, "cleaned_email", "Mailchimp")
  val emailAddressChangeSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(email_address_change, "email_address_change", "Mailchimp")
  val profileUpdateSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(profile_update, "profile_update", "Mailchimp")
}

case class MailgunSchemas(
  message_bounced: String,
  message_clicked: String,
  message_complained: String,
  message_delivered: String,
  message_dropped: String,
  message_opened: String,
  recipient_unsubscribed: String
) {
  val messageBouncedSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(message_bounced, "message_bounced", "Mailgun")
  val messageClickedSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(message_clicked, "message_clicked", "Mailgun")
  val messageComplainedSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(message_complained, "message_complained", "Mailgun")
  val messageDeliveredSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(message_delivered, "message_delivered", "Mailgun")
  val messageDroppedSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(message_dropped, "message_dropped", "Mailgun")
  val messageOpenedSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(message_opened, "message_opened", "Mailgun")
  val recipientUnsubscribedSchemaKey: SchemaKey =
    AdapterConfigHelper.toSchemaKey(recipient_unsubscribed, "recipient_unsubscribed", "Mailgun")
}

case class MandrillSchemas(
  message_bounced: String,
  message_clicked: String,
  message_delayed: String,
  message_marked_as_spam: String,
  message_opened: String,
  message_rejected: String,
  message_sent: String,
  message_soft_bounced: String,
  recipient_unsubscribed: String
) {

  val messageBouncedSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(message_bounced, "message_bounced", "Mandrill")
  val messageClickedSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(message_clicked, "message_clicked", "Mandrill")
  val messageDelayedSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(message_delayed, "message_delayed", "Mandrill")
  val messageMarkedAsSpamSchemaKey: SchemaKey =
    AdapterConfigHelper.toSchemaKey(message_marked_as_spam, "message_marked_as_spam", "Mandrill")
  val messageOpenedSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(message_opened, "message_opened", "Mandrill")
  val messageRejectedSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(message_rejected, "message_rejected", "Mandrill")
  val messageSentSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(message_sent, "message_sent", "Mandrill")
  val messageSoftBouncedSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(message_soft_bounced, "message_soft_bounced", "Mandrill")
  val recipientUnsubscribedSchemaKey: SchemaKey =
    AdapterConfigHelper.toSchemaKey(recipient_unsubscribed, "recipient_unsubscribed", "Mandrill")
}

case class MarketoSchemas(event: String) {
  val eventSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(event, "event", "Marketo")
}

case class OlarkSchemas(transcript: String, offline_message: String) {
  val transcriptSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(transcript, "transcript", "Olark")
  val offlineMessageSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(offline_message, "offline_message", "Olark")
}

case class PagerdutySchemas(incident: String) {
  val incidentSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(incident, "incident", "Pagerduty")
}

case class PingdomSchemas(
  incident_assign: String,
  incident_notify_user: String,
  incident_notify_of_close: String
) {
  val incidentAssignSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(incident_assign, "incident_assign", "Pingdom")
  val incidentNotifyUserSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(incident_notify_user, "incident_notify_user", "Pingdom")
  val incidentNotifyOfCloseSchemaKey: SchemaKey =
    AdapterConfigHelper.toSchemaKey(incident_notify_of_close, "incident_notify_of_close", "Pingdom")
}

case class StatusGatorSchemas(status_change: String) {
  val statusChangeSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(status_change, "status_change", "StatusGator")
}

case class SendgridSchemas(
  processed: String,
  dropped: String,
  delivered: String,
  deferred: String,
  bounce: String,
  open: String,
  click: String,
  spamreport: String,
  unsubscribe: String,
  group_unsubscribe: String,
  group_resubscribe: String
) {
  val processedSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(processed, "processed", "Sendgrid")
  val droppedSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(dropped, "dropped", "Sendgrid")
  val deliveredSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(delivered, "delivered", "Sendgrid")
  val deferredSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(deferred, "deferred", "Sendgrid")
  val bounceSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(bounce, "bounce", "Sendgrid")
  val openSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(open, "open", "Sendgrid")
  val clickSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(click, "click", "Sendgrid")
  val spamreportSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(spamreport, "spamreport", "Sendgrid")
  val unsubscribeSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(unsubscribe, "unsubscribe", "Sendgrid")
  val groupUnsubscribeSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(group_unsubscribe, "group_unsubscribe", "Sendgrid")
  val groupResubscribeSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(group_resubscribe, "group_resubscribe", "Sendgrid")
}

case class UnbounceSchemas(form_post: String) {
  val formPostSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(form_post, "form_post", "Unbounce")
}

case class UrbanAirshipSchemas(
  close: String,
  custom: String,
  first_open: String,
  in_app_message_display: String,
  in_app_message_expiration: String,
  in_app_message_resolution: String,
  location: String,
  open: String,
  push_body: String,
  region: String,
  rich_delete: String,
  rich_delivery: String,
  rich_head: String,
  send: String,
  tag_change: String,
  uninstall: String
) {
  val closeSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(close, "close", "UrbanAirship")
  val customSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(custom, "custom", "UrbanAirship")
  val firstOpenSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(first_open, "first_open", "UrbanAirship")
  val inAppMessageDisplaySchemaKey: SchemaKey =
    AdapterConfigHelper.toSchemaKey(in_app_message_display, "in_app_message_display", "UrbanAirship")
  val inAppMessageExpirationSchemaKey: SchemaKey =
    AdapterConfigHelper.toSchemaKey(in_app_message_expiration, "in_app_message_expiration", "UrbanAirship")
  val inAppMessageResolutionSchemaKey: SchemaKey =
    AdapterConfigHelper.toSchemaKey(in_app_message_resolution, "in_app_message_resolution", "UrbanAirship")
  val locationSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(location, "location", "UrbanAirship")
  val openSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(open, "open", "UrbanAirship")
  val pushBodySchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(push_body, "push_body", "UrbanAirship")
  val regionSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(region, "region", "UrbanAirship")
  val richDeleteSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(rich_delete, "rich_delete", "UrbanAirship")
  val richDeliverySchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(rich_delivery, "rich_delivery", "UrbanAirship")
  val richHeadSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(rich_head, "rich_head", "UrbanAirship")
  val sendSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(send, "send", "UrbanAirship")
  val tagChangeSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(tag_change, "tag_change", "UrbanAirship")
  val uninstallSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(uninstall, "uninstall", "UrbanAirship")
}

case class VeroSchemas(
  bounced: String,
  clicked: String,
  delivered: String,
  opened: String,
  sent: String,
  unsubscribed: String,
  created: String,
  updated: String
) {
  val bouncedSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(bounced, "bounced", "Vero")
  val clickedSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(clicked, "clicked", "Vero")
  val deliveredSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(delivered, "delivered", "Vero")
  val openedSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(opened, "opened", "Vero")
  val sentSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(sent, "sent", "Vero")
  val unsubscribedSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(unsubscribed, "unsubscribed", "Vero")
  val createdSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(created, "created", "Vero")
  val updatedSchemaKey: SchemaKey = AdapterConfigHelper.toSchemaKey(updated, "updated", "Vero")
}

private object AdapterConfigHelper {
  def toSchemaKey(
    uri: String,
    property: String,
    adapter: String
  ): SchemaKey =
    SchemaKey.fromUri(uri).getOrElse(throw new RuntimeException(s"$adapter: Cannot parse schema $property: $uri"))
}
