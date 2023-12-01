/*
 * Copyright (c) 2012-2022 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use enriched file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.enrich.common.outputs

import java.lang.{Integer => JInteger}
import java.lang.{Float => JFloat}
import java.lang.{Byte => JByte}
import java.math.{BigDecimal => JBigDecimal}
import java.time.format.DateTimeFormatter

import scala.beans.BeanProperty

import cats.implicits._

import io.circe.Json

import com.snowplowanalytics.snowplow.badrows.Payload.PartiallyEnrichedEvent

/**
 * The canonical output format for enriched events.
 *
 * For simplicity, we are using our Redshift format
 * as the canonical format, i.e. the below is
 * equivalent to the redshift-etl.q HiveQL script
 * used by the Hive ETL.
 *
 * When we move to Avro, we will
 * probably review some of these
 * types (e.g. move back to
 * Array for browser features, and
 * switch remaining Bytes to Booleans).
 */
// TODO: make the EnrichedEvent Avro-format, not Redshift-specific
class EnrichedEvent extends Serializable {

  // The application (site, game, app etc) enriched event belongs to, and the tracker platform
  @BeanProperty var app_id: String = _
  @BeanProperty var platform: String = _

  // Date/time
  @BeanProperty var etl_tstamp: String = _
  @BeanProperty var collector_tstamp: String = _
  @BeanProperty var dvce_created_tstamp: String = _

  // Transaction (i.e. enriched logging event)
  @BeanProperty var event: String = _
  @BeanProperty var event_id: String = _
  @BeanProperty var txn_id: JInteger = _

  // Versioning
  @BeanProperty var name_tracker: String = _
  @BeanProperty var v_tracker: String = _
  @BeanProperty var v_collector: String = _
  @BeanProperty var v_etl: String = _

  // User and visit
  @BeanProperty var user_id: String = _
  @BeanProperty var user_ipaddress: String = _
  @BeanProperty var user_fingerprint: String = _
  @BeanProperty var domain_userid: String = _
  @BeanProperty var domain_sessionidx: JInteger = _
  @BeanProperty var network_userid: String = _

  // Location
  @BeanProperty var geo_country: String = _
  @BeanProperty var geo_region: String = _
  @BeanProperty var geo_city: String = _
  @BeanProperty var geo_zipcode: String = _
  @BeanProperty var geo_latitude: JFloat = _
  @BeanProperty var geo_longitude: JFloat = _
  @BeanProperty var geo_region_name: String = _

  // Other IP lookups
  @BeanProperty var ip_isp: String = _
  @BeanProperty var ip_organization: String = _
  @BeanProperty var ip_domain: String = _
  @BeanProperty var ip_netspeed: String = _

  // Page
  @BeanProperty var page_url: String = _
  @BeanProperty var page_title: String = _
  @BeanProperty var page_referrer: String = _

  // Page URL components
  @BeanProperty var page_urlscheme: String = _
  @BeanProperty var page_urlhost: String = _
  @BeanProperty var page_urlport: JInteger = _
  @BeanProperty var page_urlpath: String = _
  @BeanProperty var page_urlquery: String = _
  @BeanProperty var page_urlfragment: String = _

  // Referrer URL components
  @BeanProperty var refr_urlscheme: String = _
  @BeanProperty var refr_urlhost: String = _
  @BeanProperty var refr_urlport: JInteger = _
  @BeanProperty var refr_urlpath: String = _
  @BeanProperty var refr_urlquery: String = _
  @BeanProperty var refr_urlfragment: String = _

  // Referrer details
  @BeanProperty var refr_medium: String = _
  @BeanProperty var refr_source: String = _
  @BeanProperty var refr_term: String = _

  // Marketing
  @BeanProperty var mkt_medium: String = _
  @BeanProperty var mkt_source: String = _
  @BeanProperty var mkt_term: String = _
  @BeanProperty var mkt_content: String = _
  @BeanProperty var mkt_campaign: String = _

  // Custom Contexts
  @BeanProperty var contexts: String = _

  // Structured Event
  @BeanProperty var se_category: String = _
  @BeanProperty var se_action: String = _
  @BeanProperty var se_label: String = _
  @BeanProperty var se_property: String = _
  @BeanProperty var se_value: JBigDecimal = _

  // Unstructured Event
  @BeanProperty var unstruct_event: String = _

  // Ecommerce transaction (from querystring)
  @BeanProperty var tr_orderid: String = _
  @BeanProperty var tr_affiliation: String = _
  @BeanProperty var tr_total: JBigDecimal = _
  @BeanProperty var tr_tax: JBigDecimal = _
  @BeanProperty var tr_shipping: JBigDecimal = _
  @BeanProperty var tr_city: String = _
  @BeanProperty var tr_state: String = _
  @BeanProperty var tr_country: String = _

  // Ecommerce transaction item (from querystring)
  @BeanProperty var ti_orderid: String = _
  @BeanProperty var ti_sku: String = _
  @BeanProperty var ti_name: String = _
  @BeanProperty var ti_category: String = _
  @BeanProperty var ti_price: JBigDecimal = _
  @BeanProperty var ti_quantity: JInteger = _

  // Page Pings
  @BeanProperty var pp_xoffset_min: JInteger = _
  @BeanProperty var pp_xoffset_max: JInteger = _
  @BeanProperty var pp_yoffset_min: JInteger = _
  @BeanProperty var pp_yoffset_max: JInteger = _

  // User Agent
  @BeanProperty var useragent: String = _

  // Browser (from user-agent)
  @BeanProperty var br_name: String = _
  @BeanProperty var br_family: String = _
  @BeanProperty var br_version: String = _
  @BeanProperty var br_type: String = _
  @BeanProperty var br_renderengine: String = _

  // Browser (from querystring)
  @BeanProperty var br_lang: String = _
  // Individual feature fields for non-Hive targets (e.g. Infobright)
  @BeanProperty var br_features_pdf: JByte = _
  @BeanProperty var br_features_flash: JByte = _
  @BeanProperty var br_features_java: JByte = _
  @BeanProperty var br_features_director: JByte = _
  @BeanProperty var br_features_quicktime: JByte = _
  @BeanProperty var br_features_realplayer: JByte = _
  @BeanProperty var br_features_windowsmedia: JByte = _
  @BeanProperty var br_features_gears: JByte = _
  @BeanProperty var br_features_silverlight: JByte = _
  @BeanProperty var br_cookies: JByte = _
  @BeanProperty var br_colordepth: String = _
  @BeanProperty var br_viewwidth: JInteger = _
  @BeanProperty var br_viewheight: JInteger = _

  // OS (from user-agent)
  @BeanProperty var os_name: String = _
  @BeanProperty var os_family: String = _
  @BeanProperty var os_manufacturer: String = _
  @BeanProperty var os_timezone: String = _

  // Device/Hardware (from user-agent)
  @BeanProperty var dvce_type: String = _
  @BeanProperty var dvce_ismobile: JByte = _

  // Device (from querystring)
  @BeanProperty var dvce_screenwidth: JInteger = _
  @BeanProperty var dvce_screenheight: JInteger = _

  // Document
  @BeanProperty var doc_charset: String = _
  @BeanProperty var doc_width: JInteger = _
  @BeanProperty var doc_height: JInteger = _

  // Currency
  @BeanProperty var tr_currency: String = _
  @BeanProperty var tr_total_base: JBigDecimal = _
  @BeanProperty var tr_tax_base: JBigDecimal = _
  @BeanProperty var tr_shipping_base: JBigDecimal = _
  @BeanProperty var ti_currency: String = _
  @BeanProperty var ti_price_base: JBigDecimal = _
  @BeanProperty var base_currency: String = _

  // Geolocation
  @BeanProperty var geo_timezone: String = _

  // Click ID
  @BeanProperty var mkt_clickid: String = _
  @BeanProperty var mkt_network: String = _

  // ETL tags
  @BeanProperty var etl_tags: String = _

  // Time event was sent
  @BeanProperty var dvce_sent_tstamp: String = _

  // Referer
  @BeanProperty var refr_domain_userid: String = _
  @BeanProperty var refr_dvce_tstamp: String = _

  // Derived contexts
  @BeanProperty var derived_contexts: String = _

  // Session ID
  @BeanProperty var domain_sessionid: String = _

  // Derived timestamp
  @BeanProperty var derived_tstamp: String = _

  // Derived event vendor/name/format/version
  @BeanProperty var event_vendor: String = _
  @BeanProperty var event_name: String = _
  @BeanProperty var event_format: String = _
  @BeanProperty var event_version: String = _

  // Event fingerprint
  @BeanProperty var event_fingerprint: String = _

  // True timestamp
  @BeanProperty var true_tstamp: String = _

  // Fields modified in PII enrichemnt (JSON String)
  @BeanProperty var pii: String = _
}

object EnrichedEvent {

  private val JsonSchemaDateTimeFormat =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

  private def toKv[T](
    k: String,
    v: T,
    f: T => Json
  ): Option[(String, Json)] =
    Option(v).map(value => (k, f(value)))

  private def toKv(k: String, s: String): Option[(String, Json)] = toKv(k, s, Json.fromString)
  private def toKv(k: String, i: JInteger): Option[(String, Json)] = toKv(k, i, (jInt: JInteger) => Json.fromInt(jInt))
  private def toKv(k: String, f: JFloat): Option[(String, Json)] = toKv(k, f, (jFloat: JFloat) => Json.fromFloatOrNull(jFloat))
  private def toKv(k: String, b: JByte): Option[(String, Json)] = toKv(k, b, (jByte: JByte) => Json.fromBoolean(jByte != 0))
  private def toKv(k: String, b: JBigDecimal): Option[(String, Json)] = toKv(k, b, (jNum: JBigDecimal) => Json.fromBigDecimal(jNum))
  private def toDateKv(k: String, s: String): Option[(String, Json)] =
    toKv(
      k,
      s,
      (s: String) => Json.fromString(DateTimeFormatter.ISO_DATE_TIME.format(JsonSchemaDateTimeFormat.parse(s)))
    )

  def toAtomic(enriched: EnrichedEvent): Either[Throwable, Json] =
    Either.catchNonFatal(
      Json.fromFields(
        toKv("app_id", enriched.app_id) ++
          toKv("platform", enriched.platform) ++
          toDateKv("etl_tstamp", enriched.etl_tstamp) ++
          toDateKv("collector_tstamp", enriched.collector_tstamp) ++
          toDateKv("dvce_created_tstamp", enriched.dvce_created_tstamp) ++
          toKv("event", enriched.event) ++
          toKv("event_id", enriched.event_id) ++
          toKv("txn_id", enriched.txn_id) ++
          toKv("name_tracker", enriched.name_tracker) ++
          toKv("v_tracker", enriched.v_tracker) ++
          toKv("v_collector", enriched.v_collector) ++
          toKv("v_etl", enriched.v_etl) ++
          toKv("user_id", enriched.user_id) ++
          toKv("user_ipaddress", enriched.user_ipaddress) ++
          toKv("user_fingerprint", enriched.user_fingerprint) ++
          toKv("domain_userid", enriched.domain_userid) ++
          toKv("domain_sessionidx", enriched.domain_sessionidx) ++
          toKv("network_userid", enriched.network_userid) ++
          toKv("geo_country", enriched.geo_country) ++
          toKv("geo_region", enriched.geo_region) ++
          toKv("geo_city", enriched.geo_city) ++
          toKv("geo_zipcode", enriched.geo_zipcode) ++
          toKv("geo_latitude", enriched.geo_latitude) ++
          toKv("geo_longitude", enriched.geo_longitude) ++
          toKv("geo_region_name", enriched.geo_region_name) ++
          toKv("ip_isp", enriched.ip_isp) ++
          toKv("ip_organization", enriched.ip_organization) ++
          toKv("ip_domain", enriched.ip_domain) ++
          toKv("ip_netspeed", enriched.ip_netspeed) ++
          toKv("page_url", enriched.page_url) ++
          toKv("page_title", enriched.page_title) ++
          toKv("page_referrer", enriched.page_referrer) ++
          toKv("page_urlscheme", enriched.page_urlscheme) ++
          toKv("page_urlhost", enriched.page_urlhost) ++
          toKv("page_urlport", enriched.page_urlport) ++
          toKv("page_urlpath", enriched.page_urlpath) ++
          toKv("page_urlquery", enriched.page_urlquery) ++
          toKv("page_urlfragment", enriched.page_urlfragment) ++
          toKv("refr_urlscheme", enriched.refr_urlscheme) ++
          toKv("refr_urlhost", enriched.refr_urlhost) ++
          toKv("refr_urlport", enriched.refr_urlport) ++
          toKv("refr_urlpath", enriched.refr_urlpath) ++
          toKv("refr_urlquery", enriched.refr_urlquery) ++
          toKv("refr_urlfragment", enriched.refr_urlfragment) ++
          toKv("refr_medium", enriched.refr_medium) ++
          toKv("refr_source", enriched.refr_source) ++
          toKv("refr_term", enriched.refr_term) ++
          toKv("mkt_medium", enriched.mkt_medium) ++
          toKv("mkt_source", enriched.mkt_source) ++
          toKv("mkt_term", enriched.mkt_term) ++
          toKv("mkt_content", enriched.mkt_content) ++
          toKv("mkt_campaign", enriched.mkt_campaign) ++
          toKv("se_category", enriched.se_category) ++
          toKv("se_action", enriched.se_action) ++
          toKv("se_label", enriched.se_label) ++
          toKv("se_property", enriched.se_property) ++
          toKv("se_value", enriched.se_value) ++
          toKv("tr_orderid", enriched.tr_orderid) ++
          toKv("tr_affiliation", enriched.tr_affiliation) ++
          toKv("tr_total", enriched.tr_total) ++
          toKv("tr_tax", enriched.tr_tax) ++
          toKv("tr_shipping", enriched.tr_shipping) ++
          toKv("tr_city", enriched.tr_city) ++
          toKv("tr_state", enriched.tr_state) ++
          toKv("tr_country", enriched.tr_country) ++
          toKv("ti_orderid", enriched.ti_orderid) ++
          toKv("ti_sku", enriched.ti_sku) ++
          toKv("ti_name", enriched.ti_name) ++
          toKv("ti_category", enriched.ti_category) ++
          toKv("ti_price", enriched.ti_price) ++
          toKv("ti_quantity", enriched.ti_quantity) ++
          toKv("pp_xoffset_min", enriched.pp_xoffset_min) ++
          toKv("pp_xoffset_max", enriched.pp_xoffset_max) ++
          toKv("pp_yoffset_min", enriched.pp_yoffset_min) ++
          toKv("pp_yoffset_max", enriched.pp_yoffset_max) ++
          toKv("useragent", enriched.useragent) ++
          toKv("br_name", enriched.br_name) ++
          toKv("br_family", enriched.br_family) ++
          toKv("br_version", enriched.br_version) ++
          toKv("br_type", enriched.br_type) ++
          toKv("br_renderengine", enriched.br_renderengine) ++
          toKv("br_lang", enriched.br_lang) ++
          toKv("br_features_pdf", enriched.br_features_pdf) ++
          toKv("br_features_flash", enriched.br_features_flash) ++
          toKv("br_features_java", enriched.br_features_java) ++
          toKv("br_features_director", enriched.br_features_director) ++
          toKv("br_features_quicktime", enriched.br_features_quicktime) ++
          toKv("br_features_realplayer", enriched.br_features_realplayer) ++
          toKv("br_features_windowsmedia", enriched.br_features_windowsmedia) ++
          toKv("br_features_gears", enriched.br_features_gears) ++
          toKv("br_features_silverlight", enriched.br_features_silverlight) ++
          toKv("br_cookies", enriched.br_cookies) ++
          toKv("br_colordepth", enriched.br_colordepth) ++
          toKv("br_viewwidth", enriched.br_viewwidth) ++
          toKv("br_viewheight", enriched.br_viewheight) ++
          toKv("os_name", enriched.os_name) ++
          toKv("os_family", enriched.os_family) ++
          toKv("os_manufacturer", enriched.os_manufacturer) ++
          toKv("os_timezone", enriched.os_timezone) ++
          toKv("dvce_type", enriched.dvce_type) ++
          toKv("dvce_ismobile", enriched.dvce_ismobile) ++
          toKv("dvce_screenwidth", enriched.dvce_screenwidth) ++
          toKv("dvce_screenheight", enriched.dvce_screenheight) ++
          toKv("doc_charset", enriched.doc_charset) ++
          toKv("doc_width", enriched.doc_width) ++
          toKv("doc_height", enriched.doc_height) ++
          toKv("tr_currency", enriched.tr_currency) ++
          toKv("tr_total_base", enriched.tr_total_base) ++
          toKv("tr_tax_base", enriched.tr_tax_base) ++
          toKv("tr_shipping_base", enriched.tr_shipping_base) ++
          toKv("ti_currency", enriched.ti_currency) ++
          toKv("ti_price_base", enriched.ti_price_base) ++
          toKv("base_currency", enriched.base_currency) ++
          toKv("geo_timezone", enriched.geo_timezone) ++
          toKv("mkt_clickid", enriched.mkt_clickid) ++
          toKv("mkt_network", enriched.mkt_network) ++
          toKv("etl_tags", enriched.etl_tags) ++
          toDateKv("dvce_sent_tstamp", enriched.dvce_sent_tstamp) ++
          toKv("refr_domain_userid", enriched.refr_domain_userid) ++
          toDateKv("refr_dvce_tstamp", enriched.refr_dvce_tstamp) ++
          toKv("domain_sessionid", enriched.domain_sessionid) ++
          toDateKv("derived_tstamp", enriched.derived_tstamp) ++
          toKv("event_vendor", enriched.event_vendor) ++
          toKv("event_name", enriched.event_name) ++
          toKv("event_format", enriched.event_format) ++
          toKv("event_version", enriched.event_version) ++
          toKv("event_fingerprint", enriched.event_fingerprint) ++
          toDateKv("true_tstamp", enriched.true_tstamp)
      )
    )

  def toPartiallyEnrichedEvent(enrichedEvent: EnrichedEvent): PartiallyEnrichedEvent =
    PartiallyEnrichedEvent(
      app_id = Option(enrichedEvent.app_id),
      platform = Option(enrichedEvent.platform),
      etl_tstamp = Option(enrichedEvent.etl_tstamp),
      collector_tstamp = Option(enrichedEvent.collector_tstamp),
      dvce_created_tstamp = Option(enrichedEvent.dvce_created_tstamp),
      event = Option(enrichedEvent.event),
      event_id = Option(enrichedEvent.event_id),
      txn_id = Option(enrichedEvent.txn_id).map(_.toString),
      name_tracker = Option(enrichedEvent.name_tracker),
      v_tracker = Option(enrichedEvent.v_tracker),
      v_collector = Option(enrichedEvent.v_collector),
      v_etl = Option(enrichedEvent.v_etl),
      user_id = Option(enrichedEvent.user_id),
      user_ipaddress = Option(enrichedEvent.user_ipaddress),
      user_fingerprint = Option(enrichedEvent.user_fingerprint),
      domain_userid = Option(enrichedEvent.domain_userid),
      domain_sessionidx = Option(enrichedEvent.domain_sessionidx).map(Integer2int),
      network_userid = Option(enrichedEvent.network_userid),
      geo_country = Option(enrichedEvent.geo_country),
      geo_region = Option(enrichedEvent.geo_region),
      geo_city = Option(enrichedEvent.geo_city),
      geo_zipcode = Option(enrichedEvent.geo_zipcode),
      geo_latitude = Option(enrichedEvent.geo_latitude).map(Float2float),
      geo_longitude = Option(enrichedEvent.geo_longitude).map(Float2float),
      geo_region_name = Option(enrichedEvent.geo_region_name),
      ip_isp = Option(enrichedEvent.ip_isp),
      ip_organization = Option(enrichedEvent.ip_organization),
      ip_domain = Option(enrichedEvent.ip_domain),
      ip_netspeed = Option(enrichedEvent.ip_netspeed),
      page_url = Option(enrichedEvent.page_url),
      page_title = Option(enrichedEvent.page_title),
      page_referrer = Option(enrichedEvent.page_referrer),
      page_urlscheme = Option(enrichedEvent.page_urlscheme),
      page_urlhost = Option(enrichedEvent.page_urlhost),
      page_urlport = Option(enrichedEvent.page_urlport).map(Integer2int),
      page_urlpath = Option(enrichedEvent.page_urlpath),
      page_urlquery = Option(enrichedEvent.page_urlquery),
      page_urlfragment = Option(enrichedEvent.page_urlfragment),
      refr_urlscheme = Option(enrichedEvent.refr_urlscheme),
      refr_urlhost = Option(enrichedEvent.refr_urlhost),
      refr_urlport = Option(enrichedEvent.refr_urlport).map(Integer2int),
      refr_urlpath = Option(enrichedEvent.refr_urlpath),
      refr_urlquery = Option(enrichedEvent.refr_urlquery),
      refr_urlfragment = Option(enrichedEvent.refr_urlfragment),
      refr_medium = Option(enrichedEvent.refr_medium),
      refr_source = Option(enrichedEvent.refr_source),
      refr_term = Option(enrichedEvent.refr_term),
      mkt_medium = Option(enrichedEvent.mkt_medium),
      mkt_source = Option(enrichedEvent.mkt_source),
      mkt_term = Option(enrichedEvent.mkt_term),
      mkt_content = Option(enrichedEvent.mkt_content),
      mkt_campaign = Option(enrichedEvent.mkt_campaign),
      contexts = Option(enrichedEvent.contexts),
      se_category = Option(enrichedEvent.se_category),
      se_action = Option(enrichedEvent.se_action),
      se_label = Option(enrichedEvent.se_label),
      se_property = Option(enrichedEvent.se_property),
      se_value = Option(enrichedEvent.se_value).map(_.toString),
      unstruct_event = Option(enrichedEvent.unstruct_event),
      tr_orderid = Option(enrichedEvent.tr_orderid),
      tr_affiliation = Option(enrichedEvent.tr_affiliation),
      tr_total = Option(enrichedEvent.tr_total).map(_.toString),
      tr_tax = Option(enrichedEvent.tr_tax).map(_.toString),
      tr_shipping = Option(enrichedEvent.tr_shipping).map(_.toString),
      tr_city = Option(enrichedEvent.tr_city),
      tr_state = Option(enrichedEvent.tr_state),
      tr_country = Option(enrichedEvent.tr_country),
      ti_orderid = Option(enrichedEvent.ti_orderid),
      ti_sku = Option(enrichedEvent.ti_sku),
      ti_name = Option(enrichedEvent.ti_name),
      ti_category = Option(enrichedEvent.ti_category),
      ti_price = Option(enrichedEvent.ti_price).map(_.toString),
      ti_quantity = Option(enrichedEvent.ti_quantity).map(Integer2int),
      pp_xoffset_min = Option(enrichedEvent.pp_xoffset_min).map(Integer2int),
      pp_xoffset_max = Option(enrichedEvent.pp_xoffset_max).map(Integer2int),
      pp_yoffset_min = Option(enrichedEvent.pp_yoffset_min).map(Integer2int),
      pp_yoffset_max = Option(enrichedEvent.pp_yoffset_max).map(Integer2int),
      useragent = Option(enrichedEvent.useragent),
      br_name = Option(enrichedEvent.br_name),
      br_family = Option(enrichedEvent.br_family),
      br_version = Option(enrichedEvent.br_version),
      br_type = Option(enrichedEvent.br_type),
      br_renderengine = Option(enrichedEvent.br_renderengine),
      br_lang = Option(enrichedEvent.br_lang),
      br_features_pdf = Option(enrichedEvent.br_features_pdf).map(Byte2byte),
      br_features_flash = Option(enrichedEvent.br_features_flash).map(Byte2byte),
      br_features_java = Option(enrichedEvent.br_features_java).map(Byte2byte),
      br_features_director = Option(enrichedEvent.br_features_director).map(Byte2byte),
      br_features_quicktime = Option(enrichedEvent.br_features_quicktime).map(Byte2byte),
      br_features_realplayer = Option(enrichedEvent.br_features_realplayer).map(Byte2byte),
      br_features_windowsmedia = Option(enrichedEvent.br_features_windowsmedia).map(Byte2byte),
      br_features_gears = Option(enrichedEvent.br_features_gears).map(Byte2byte),
      br_features_silverlight = Option(enrichedEvent.br_features_silverlight).map(Byte2byte),
      br_cookies = Option(enrichedEvent.br_cookies).map(Byte2byte),
      br_colordepth = Option(enrichedEvent.br_colordepth),
      br_viewwidth = Option(enrichedEvent.br_viewwidth).map(Integer2int),
      br_viewheight = Option(enrichedEvent.br_viewheight).map(Integer2int),
      os_name = Option(enrichedEvent.os_name),
      os_family = Option(enrichedEvent.os_family),
      os_manufacturer = Option(enrichedEvent.os_manufacturer),
      os_timezone = Option(enrichedEvent.os_timezone),
      dvce_type = Option(enrichedEvent.dvce_type),
      dvce_ismobile = Option(enrichedEvent.dvce_ismobile).map(Byte2byte),
      dvce_screenwidth = Option(enrichedEvent.dvce_screenwidth).map(Integer2int),
      dvce_screenheight = Option(enrichedEvent.dvce_screenheight).map(Integer2int),
      doc_charset = Option(enrichedEvent.doc_charset),
      doc_width = Option(enrichedEvent.doc_width).map(Integer2int),
      doc_height = Option(enrichedEvent.doc_height).map(Integer2int),
      tr_currency = Option(enrichedEvent.tr_currency),
      tr_total_base = Option(enrichedEvent.tr_total_base).map(_.toString),
      tr_tax_base = Option(enrichedEvent.tr_tax_base).map(_.toString),
      tr_shipping_base = Option(enrichedEvent.tr_shipping_base).map(_.toString),
      ti_currency = Option(enrichedEvent.ti_currency),
      ti_price_base = Option(enrichedEvent.ti_price_base).map(_.toString),
      base_currency = Option(enrichedEvent.base_currency),
      geo_timezone = Option(enrichedEvent.geo_timezone),
      mkt_clickid = Option(enrichedEvent.mkt_clickid),
      mkt_network = Option(enrichedEvent.mkt_network),
      etl_tags = Option(enrichedEvent.etl_tags),
      dvce_sent_tstamp = Option(enrichedEvent.dvce_sent_tstamp),
      refr_domain_userid = Option(enrichedEvent.refr_domain_userid),
      refr_dvce_tstamp = Option(enrichedEvent.refr_dvce_tstamp),
      derived_contexts = Option(enrichedEvent.derived_contexts),
      domain_sessionid = Option(enrichedEvent.domain_sessionid),
      derived_tstamp = Option(enrichedEvent.derived_tstamp),
      event_vendor = Option(enrichedEvent.event_vendor),
      event_name = Option(enrichedEvent.event_name),
      event_format = Option(enrichedEvent.event_format),
      event_version = Option(enrichedEvent.event_version),
      event_fingerprint = Option(enrichedEvent.event_fingerprint),
      true_tstamp = Option(enrichedEvent.true_tstamp)
    )
}
