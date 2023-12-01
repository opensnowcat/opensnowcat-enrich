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
package com.snowplowanalytics.snowplow.enrich.common.adapters

import java.time.Instant

import cats.Monad
import cats.data.{NonEmptyList, Validated}

import cats.effect.Clock
import cats.implicits._

import com.snowplowanalytics.iglu.client.resolver.registries.RegistryLookup
import com.snowplowanalytics.iglu.client.IgluCirceClient

import com.snowplowanalytics.snowplow.badrows._

import com.snowplowanalytics.snowplow.enrich.common.adapters.registry._
import com.snowplowanalytics.snowplow.enrich.common.adapters.registry.snowplow.{RedirectAdapter, Tp1Adapter, Tp2Adapter}
import com.snowplowanalytics.snowplow.enrich.common.loaders.CollectorPayload
import com.snowplowanalytics.snowplow.enrich.common.utils.HttpClient

/**
 * The AdapterRegistry lets us convert a CollectorPayload into one or more RawEvents, using a given
 * adapter.
 */
class AdapterRegistry(
  remoteAdapters: Map[(String, String), RemoteAdapter] = Map.empty,
  adaptersSchemas: AdaptersSchemas
) {
  val adapters: Map[(String, String), Adapter] = Map(
    (Vendor.Snowplow, "tp1") -> Tp1Adapter,
    (Vendor.Snowplow, "tp2") -> Tp2Adapter,
    (Vendor.Redirect, "tp2") -> RedirectAdapter,
    (Vendor.Iglu, "v1") -> IgluAdapter,
    (Vendor.Callrail, "v1") -> CallrailAdapter(adaptersSchemas.callrail),
    (Vendor.Cloudfront, "wd_access_log") -> CloudfrontAccessLogAdapter(adaptersSchemas.cloudfrontAccessLog),
    (Vendor.Mailchimp, "v1") -> MailchimpAdapter(adaptersSchemas.mailchimp),
    (Vendor.Mailgun, "v1") -> MailgunAdapter(adaptersSchemas.mailgun),
    (Vendor.GoogleAnalytics, "v1") -> GoogleAnalyticsAdapter(adaptersSchemas.googleAnalytics),
    (Vendor.Mandrill, "v1") -> MandrillAdapter(adaptersSchemas.mandrill),
    (Vendor.Olark, "v1") -> OlarkAdapter(adaptersSchemas.olark),
    (Vendor.Pagerduty, "v1") -> PagerdutyAdapter(adaptersSchemas.pagerduty),
    (Vendor.Pingdom, "v1") -> PingdomAdapter(adaptersSchemas.pingdom),
    (Vendor.Sendgrid, "v3") -> SendgridAdapter(adaptersSchemas.sendgrid),
    (Vendor.StatusGator, "v1") -> StatusGatorAdapter(adaptersSchemas.statusgator),
    (Vendor.Unbounce, "v1") -> UnbounceAdapter(adaptersSchemas.unbounce),
    (Vendor.UrbanAirship, "v1") -> UrbanAirshipAdapter(adaptersSchemas.urbanAirship),
    (Vendor.Marketo, "v1") -> MarketoAdapter(adaptersSchemas.marketo),
    (Vendor.Vero, "v1") -> VeroAdapter(adaptersSchemas.vero),
    (Vendor.HubSpot, "v1") -> HubSpotAdapter(adaptersSchemas.hubspot)
  ) ++ remoteAdapters

  private object Vendor {
    val Snowplow = "com.snowplowanalytics.snowplow"
    val Redirect = "r"
    val Iglu = "com.snowplowanalytics.iglu"
    val Callrail = "com.callrail"
    val Cloudfront = "com.amazon.aws.cloudfront"
    val GoogleAnalytics = "com.google.analytics"
    val Mailchimp = "com.mailchimp"
    val Mailgun = "com.mailgun"
    val Mandrill = "com.mandrill"
    val Olark = "com.olark"
    val Pagerduty = "com.pagerduty"
    val Pingdom = "com.pingdom"
    val Sendgrid = "com.sendgrid"
    val StatusGator = "com.statusgator"
    val Unbounce = "com.unbounce"
    val UrbanAirship = "com.urbanairship.connect"
    val Marketo = "com.marketo"
    val Vero = "com.getvero"
    val HubSpot = "com.hubspot"
  }

  /**
   * Router to determine which adapter we use to convert the CollectorPayload into one or more
   * RawEvents.
   * @param payload The CollectorPayload we are transforming
   * @param client The Iglu client used for schema lookup and validation
   * @return a Validation boxing either a NEL of RawEvents on Success, or a NEL of Strings on
   * Failure
   */
  def toRawEvents[F[_]: Monad: RegistryLookup: Clock: HttpClient](
    payload: CollectorPayload,
    client: IgluCirceClient[F],
    processor: Processor
  ): F[Validated[BadRow, NonEmptyList[RawEvent]]] =
    (adapters.get((payload.api.vendor, payload.api.version)) match {
      case Some(adapter) => adapter.toRawEvents(payload, client)
      case None =>
        val f = FailureDetails.AdapterFailure.InputData(
          "vendor/version",
          Some(s"${payload.api.vendor}/${payload.api.version}"),
          "vendor/version combination is not supported"
        )
        Monad[F].pure(f.invalidNel[NonEmptyList[RawEvent]])
    }).map(_.leftMap(enrichFailure(_, payload, payload.api.vendor, payload.api.version, processor)))

  private def enrichFailure(
    fs: NonEmptyList[FailureDetails.AdapterFailureOrTrackerProtocolViolation],
    cp: CollectorPayload,
    vendor: String,
    vendorVersion: String,
    processor: Processor
  ): BadRow = {
    val payload = cp.toBadRowPayload
    if (vendorVersion == "tp2" && (vendor == Vendor.Snowplow || vendor == Vendor.Redirect)) {
      val tpViolations = fs.asInstanceOf[NonEmptyList[FailureDetails.TrackerProtocolViolation]]
      val failure =
        Failure.TrackerProtocolViolations(Instant.now(), vendor, vendorVersion, tpViolations)
      BadRow.TrackerProtocolViolations(processor, failure, payload)
    } else {
      val adapterFailures = fs.asInstanceOf[NonEmptyList[FailureDetails.AdapterFailure]]
      val failure = Failure.AdapterFailures(Instant.now(), vendor, vendorVersion, adapterFailures)
      BadRow.AdapterFailures(processor, failure, payload)
    }
  }
}
