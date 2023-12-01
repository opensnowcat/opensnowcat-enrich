/*
 * Copyright (c) 2013-2022 Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0, and
 * you may not use this file except in compliance with the Apache License
 * Version 2.0.  You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Apache License Version 2.0 is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.enrich.common.outputs

import java.lang.{Integer => JInteger}
import java.lang.{Float => JFloat}
import java.lang.{Byte => JByte}
import java.math.{BigDecimal => JBigDecimal}

import com.snowplowanalytics.snowplow.badrows.Payload.PartiallyEnrichedEvent
import org.specs2.mutable.Specification

class EnrichedEventSpec extends Specification {
  import EnrichedEventSpec._

  "toPartiallyEnrichedEvents" should {
    "Convert missing fields to None" in {
      val ee = new EnrichedEvent()
      val pe = EnrichedEvent.toPartiallyEnrichedEvent(ee)

      fieldsOf(pe) must contain(beNone).forall
    }

    "Convert defined fields to None" in {
      def testField[A](setField: EnrichedEvent => Unit, getField: PartiallyEnrichedEvent => Option[A]) = {
        val ee = new EnrichedEvent()
        setField(ee)
        val pe = EnrichedEvent.toPartiallyEnrichedEvent(ee)
        getField(pe) must beSome
      }

      testField(_.app_id = "app_id", _.app_id)
      testField(_.platform = "platform", _.platform)
      testField(_.etl_tstamp = "etl_tstamp", _.etl_tstamp)
      testField(_.collector_tstamp = "etl_tstamp", _.collector_tstamp)
      testField(_.event = "event", _.event)
      testField(_.event_id = "event_id", _.event_id)
      testField(_.txn_id = JInteger.valueOf(0), _.txn_id)
      testField(_.name_tracker = "name_tracker", _.name_tracker)
      testField(_.v_tracker = "v_tracker", _.v_tracker)
      testField(_.v_collector = "v_collector", _.v_collector)
      testField(_.v_etl = "v_etl", _.v_etl)
      testField(_.user_id = "user_id", _.user_id)
      testField(_.user_ipaddress = "user_ipaddress", _.user_ipaddress)
      testField(_.user_fingerprint = "user_fingerprint", _.user_fingerprint)
      testField(_.domain_userid = "domain_userid", _.domain_userid)
      testField(_.domain_sessionidx = JInteger.valueOf(0), _.domain_sessionidx)
      testField(_.network_userid = "network_userid", _.network_userid)
      testField(_.geo_country = "geo_country", _.geo_country)
      testField(_.geo_region = "geo_region", _.geo_region)
      testField(_.geo_city = "geo_city", _.geo_city)
      testField(_.geo_zipcode = "geo_zipcode", _.geo_zipcode)
      testField(_.geo_latitude = JFloat.valueOf("0.0"), _.geo_latitude)
      testField(_.geo_longitude = JFloat.valueOf("0.0"), _.geo_longitude)
      testField(_.geo_region_name = "geo_region_name", _.geo_region_name)
      testField(_.ip_isp = "ip_isp", _.ip_isp)
      testField(_.ip_organization = "ip_organization", _.ip_organization)
      testField(_.ip_domain = "ip_domain", _.ip_domain)
      testField(_.ip_netspeed = "ip_netspeed", _.ip_netspeed)
      testField(_.page_url = "page_url", _.page_url)
      testField(_.page_title = "page_title", _.page_title)
      testField(_.page_referrer = "page_referrer", _.page_referrer)
      testField(_.page_urlscheme = "page_urlscheme", _.page_urlscheme)
      testField(_.page_urlhost = "page_urlhost", _.page_urlhost)
      testField(_.page_urlport = JInteger.valueOf(0), _.page_urlport)
      testField(_.page_urlpath = "page_urlpath", _.page_urlpath)
      testField(_.page_urlquery = "page_urlquery", _.page_urlquery)
      testField(_.page_urlfragment = "page_urlfragment", _.page_urlfragment)
      testField(_.refr_urlscheme = "refr_urlscheme", _.refr_urlscheme)
      testField(_.refr_urlhost = "refr_urlhost", _.refr_urlhost)
      testField(_.refr_urlport = JInteger.valueOf(0), _.refr_urlport)
      testField(_.refr_urlpath = "refr_urlpath", _.refr_urlpath)
      testField(_.refr_urlquery = "refr_urlquery", _.refr_urlquery)
      testField(_.refr_urlfragment = "refr_urlfragment", _.refr_urlfragment)
      testField(_.refr_medium = "refr_medium", _.refr_medium)
      testField(_.refr_source = "refr_source", _.refr_source)
      testField(_.refr_term = "refr_term", _.refr_term)
      testField(_.mkt_medium = "mkt_medium", _.mkt_medium)
      testField(_.mkt_source = "mkt_source", _.mkt_source)
      testField(_.mkt_term = "mkt_term", _.mkt_term)
      testField(_.mkt_content = "mkt_content", _.mkt_content)
      testField(_.mkt_campaign = "mkt_campaign", _.mkt_campaign)
      testField(_.contexts = "contexts", _.contexts)
      testField(_.se_category = "se_category", _.se_category)
      testField(_.se_action = "se_action", _.se_action)
      testField(_.se_label = "se_label", _.se_label)
      testField(_.se_property = "se_property", _.se_property)
      testField(_.se_value = new JBigDecimal("0.0"), _.se_value)
      testField(_.unstruct_event = "unstruct_event", _.unstruct_event)
      testField(_.tr_orderid = "tr_orderid", _.tr_orderid)
      testField(_.tr_affiliation = "tr_affiliation", _.tr_affiliation)
      testField(_.tr_total = new JBigDecimal("0.0"), _.tr_total)
      testField(_.tr_tax = new JBigDecimal("0.0"), _.tr_tax)
      testField(_.tr_shipping = new JBigDecimal("0.0"), _.tr_shipping)
      testField(_.tr_city = "tr_city", _.tr_city)
      testField(_.tr_state = "tr_state", _.tr_state)
      testField(_.tr_country = "tr_country", _.tr_country)
      testField(_.ti_orderid = "ti_orderid", _.ti_orderid)
      testField(_.ti_sku = "ti_sku", _.ti_sku)
      testField(_.ti_name = "ti_name", _.ti_name)
      testField(_.ti_category = "ti_category", _.ti_category)
      testField(_.ti_price = new JBigDecimal("0.0"), _.ti_price)
      testField(_.ti_quantity = JInteger.valueOf(0), _.ti_quantity)
      testField(_.pp_xoffset_min = JInteger.valueOf(0), _.pp_xoffset_min)
      testField(_.pp_xoffset_max = JInteger.valueOf(0), _.pp_xoffset_max)
      testField(_.pp_yoffset_min = JInteger.valueOf(0), _.pp_yoffset_min)
      testField(_.pp_yoffset_max = JInteger.valueOf(0), _.pp_yoffset_max)
      testField(_.useragent = "useragent", _.useragent)
      testField(_.br_name = "br_name", _.br_name)
      testField(_.br_family = "br_family", _.br_family)
      testField(_.br_version = "br_version", _.br_version)
      testField(_.br_type = "br_type", _.br_type)
      testField(_.br_renderengine = "br_renderengine", _.br_renderengine)
      testField(_.br_lang = "br_lang", _.br_lang)
      testField(_.br_features_pdf = JByte.valueOf(Byte.MinValue), _.br_features_pdf)
      testField(_.br_features_flash = JByte.valueOf(Byte.MinValue), _.br_features_flash)
      testField(_.br_features_java = JByte.valueOf(Byte.MinValue), _.br_features_java)
      testField(_.br_features_director = JByte.valueOf(Byte.MinValue), _.br_features_director)
      testField(_.br_features_quicktime = JByte.valueOf(Byte.MinValue), _.br_features_quicktime)
      testField(_.br_features_realplayer = JByte.valueOf(Byte.MinValue), _.br_features_realplayer)
      testField(_.br_features_windowsmedia = JByte.valueOf(Byte.MinValue), _.br_features_windowsmedia)
      testField(_.br_features_gears = JByte.valueOf(Byte.MinValue), _.br_features_gears)
      testField(_.br_features_silverlight = JByte.valueOf(Byte.MinValue), _.br_features_silverlight)
      testField(_.br_cookies = JByte.valueOf(Byte.MinValue), _.br_cookies)
      testField(_.br_colordepth = "br_colordepth", _.br_colordepth)
      testField(_.br_viewwidth = JInteger.valueOf(0), _.br_viewwidth)
      testField(_.br_viewheight = JInteger.valueOf(0), _.br_viewheight)
      testField(_.os_name = "os_name", _.os_name)
      testField(_.os_family = "os_family", _.os_family)
      testField(_.os_manufacturer = "os_manufacturer", _.os_manufacturer)
      testField(_.os_timezone = "os_timezone", _.os_timezone)
      testField(_.dvce_type = "dvce_type", _.dvce_type)
      testField(_.dvce_ismobile = JByte.valueOf(Byte.MinValue), _.dvce_ismobile)
      testField(_.dvce_screenwidth = JInteger.valueOf(0), _.dvce_screenwidth)
      testField(_.dvce_screenheight = JInteger.valueOf(0), _.dvce_screenheight)
      testField(_.doc_charset = "doc_charset", _.doc_charset)
      testField(_.doc_width = JInteger.valueOf(0), _.doc_width)
      testField(_.doc_height = JInteger.valueOf(0), _.doc_height)
      testField(_.tr_currency = "tr_currency", _.tr_currency)
      testField(_.tr_total_base = new JBigDecimal("0.0"), _.tr_total_base)
      testField(_.tr_tax_base = new JBigDecimal("0.0"), _.tr_tax_base)
      testField(_.tr_shipping_base = new JBigDecimal("0.0"), _.tr_shipping_base)
      testField(_.ti_currency = "ti_currency", _.ti_currency)
      testField(_.ti_price_base = new JBigDecimal("0.0"), _.ti_price_base)
      testField(_.base_currency = "base_currency", _.base_currency)
      testField(_.geo_timezone = "geo_timezone", _.geo_timezone)
      testField(_.mkt_clickid = "mkt_clickid", _.mkt_clickid)
      testField(_.mkt_network = "mkt_network", _.mkt_network)
      testField(_.etl_tags = "etl_tags", _.etl_tags)
      testField(_.dvce_sent_tstamp = "dvce_sent_tstamp", _.dvce_sent_tstamp)
      testField(_.refr_domain_userid = "refr_domain_userid", _.refr_domain_userid)
      testField(_.refr_dvce_tstamp = "refr_dvce_tstamp", _.refr_dvce_tstamp)
      testField(_.derived_contexts = "derived_contexts", _.derived_contexts)
      testField(_.domain_sessionid = "domain_sessionid", _.domain_sessionid)
      testField(_.derived_tstamp = "derived_tstamp", _.derived_tstamp)
      testField(_.event_vendor = "event_vendor", _.event_vendor)
      testField(_.event_name = "event_name", _.event_name)
      testField(_.event_format = "event_format", _.event_format)
      testField(_.event_version = "event_version", _.event_version)
      testField(_.event_fingerprint = "event_fingerprint", _.event_fingerprint)
      testField(_.true_tstamp = "true_tstamp", _.true_tstamp)
    }
  }

}

object EnrichedEventSpec {

  def fieldsOf(pe: PartiallyEnrichedEvent): List[Option[Any]] =
    List(
      pe.app_id,
      pe.platform,
      pe.etl_tstamp,
      pe.collector_tstamp,
      pe.dvce_created_tstamp,
      pe.event,
      pe.event_id,
      pe.txn_id,
      pe.name_tracker,
      pe.v_tracker,
      pe.v_collector,
      pe.v_etl,
      pe.user_id,
      pe.user_ipaddress,
      pe.user_fingerprint,
      pe.domain_userid,
      pe.domain_sessionidx,
      pe.network_userid,
      pe.geo_country,
      pe.geo_region,
      pe.geo_city,
      pe.geo_zipcode,
      pe.geo_latitude,
      pe.geo_longitude,
      pe.geo_region_name,
      pe.ip_isp,
      pe.ip_organization,
      pe.ip_domain,
      pe.ip_netspeed,
      pe.page_url,
      pe.page_title,
      pe.page_referrer,
      pe.page_urlscheme,
      pe.page_urlhost,
      pe.page_urlport,
      pe.page_urlpath,
      pe.page_urlquery,
      pe.page_urlfragment,
      pe.refr_urlscheme,
      pe.refr_urlhost,
      pe.refr_urlport,
      pe.refr_urlpath,
      pe.refr_urlquery,
      pe.refr_urlfragment,
      pe.refr_medium,
      pe.refr_source,
      pe.refr_term,
      pe.mkt_medium,
      pe.mkt_source,
      pe.mkt_term,
      pe.mkt_content,
      pe.mkt_campaign,
      pe.contexts,
      pe.se_category,
      pe.se_action,
      pe.se_label,
      pe.se_property,
      pe.se_value,
      pe.unstruct_event,
      pe.tr_orderid,
      pe.tr_affiliation,
      pe.tr_total,
      pe.tr_tax,
      pe.tr_shipping,
      pe.tr_city,
      pe.tr_state,
      pe.tr_country,
      pe.ti_orderid,
      pe.ti_sku,
      pe.ti_name,
      pe.ti_category,
      pe.ti_price,
      pe.ti_quantity,
      pe.pp_xoffset_min,
      pe.pp_xoffset_max,
      pe.pp_yoffset_min,
      pe.pp_yoffset_max,
      pe.useragent,
      pe.br_name,
      pe.br_family,
      pe.br_version,
      pe.br_type,
      pe.br_renderengine,
      pe.br_lang,
      pe.br_features_pdf,
      pe.br_features_flash,
      pe.br_features_java,
      pe.br_features_director,
      pe.br_features_quicktime,
      pe.br_features_realplayer,
      pe.br_features_windowsmedia,
      pe.br_features_gears,
      pe.br_features_silverlight,
      pe.br_cookies,
      pe.br_colordepth,
      pe.br_viewwidth,
      pe.br_viewheight,
      pe.os_name,
      pe.os_family,
      pe.os_manufacturer,
      pe.os_timezone,
      pe.dvce_type,
      pe.dvce_ismobile,
      pe.dvce_screenwidth,
      pe.dvce_screenheight,
      pe.doc_charset,
      pe.doc_width,
      pe.doc_height,
      pe.tr_currency,
      pe.tr_total_base,
      pe.tr_tax_base,
      pe.tr_shipping_base,
      pe.ti_currency,
      pe.ti_price_base,
      pe.base_currency,
      pe.geo_timezone,
      pe.mkt_clickid,
      pe.mkt_network,
      pe.etl_tags,
      pe.dvce_sent_tstamp,
      pe.refr_domain_userid,
      pe.refr_dvce_tstamp,
      pe.derived_contexts,
      pe.domain_sessionid,
      pe.derived_tstamp,
      pe.event_vendor,
      pe.event_name,
      pe.event_format,
      pe.event_version,
      pe.event_fingerprint,
      pe.true_tstamp
    )
}
