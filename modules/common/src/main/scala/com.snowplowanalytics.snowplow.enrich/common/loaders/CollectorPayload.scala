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
package com.snowplowanalytics.snowplow.enrich.common.loaders

import java.util.UUID

import scala.collection.JavaConverters._

import cats.syntax.either._
import cats.syntax.option._

import org.apache.http.NameValuePair
import org.apache.http.client.utils.URIBuilder
import org.apache.thrift.TSerializer

import org.joda.time.DateTime

import com.snowplowanalytics.iglu.core.{SchemaKey, SchemaVer}

import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.{CollectorPayload => CollectorPayload1}

import com.snowplowanalytics.snowplow.badrows.{FailureDetails, NVP, Payload}

/**
 * The canonical input format for the ETL process: it should be possible to convert any collector
 * input format to this format, ready for the main, collector-agnostic stage of the ETL.
 *
 * Unlike `RawEvent`, where `parameters` contain a single event,
 * [[CollectorPayload]]'s `body` can contain a POST payload with multiple events,
 * hence [[CollectorPayload]] with `body` is potentially identical to `List[RawEvent]`
 * or [[CollectorPayload]] with `querystring` is identical to single `RawEvent`
 *
 * @param api collector's endpoint
 * @param querystring GET parameters, would be empty for buffered events and most webhooks,
 *                    an actual payload of `body` is empty
 * @param contentType derived from HTTP header (should be in `Context`)
 * @param body POST body, for buffered events and most webhooks,
 *             an actual payload if `querystring` is empty
 * @param source information to identify the collector
 * @param context event's meta-information, some properties can be used to augment payload
 */
final case class CollectorPayload(
  api: CollectorPayload.Api,
  querystring: List[NameValuePair],
  contentType: Option[String],
  body: Option[String],
  source: CollectorPayload.Source,
  context: CollectorPayload.Context
) {
  def toBadRowPayload: Payload.CollectorPayload =
    Payload.CollectorPayload(
      api.vendor,
      api.version,
      querystring.map(nvp => NVP(nvp.getName, Option(nvp.getValue))),
      contentType,
      body,
      source.name,
      source.encoding,
      source.hostname,
      context.timestamp,
      context.ipAddress,
      context.useragent,
      context.refererUri,
      context.headers,
      context.userId
    )

  /**
   * Cast back to Thrift-generated `CollectorPayload` class, coming from collector
   * Reverse of ThriftLoader.toCollectorPayload
   * Used for tests and debugging
   */
  def toThrift: CollectorPayload1 = {
    // Timestamp must be always set, otherwise long will fallback it to 1970-01-01
    val timestamp: Long = context.timestamp.map(_.getMillis.asInstanceOf[java.lang.Long]).orNull

    new CollectorPayload1(CollectorPayload.IgluUri.toSchemaUri, context.ipAddress.orNull, timestamp, source.encoding, source.name)
      .setQuerystring((new URIBuilder).setParameters(querystring.asJava).build().getQuery)
      .setHostname(source.hostname.orNull)
      .setRefererUri(context.refererUri.orNull)
      .setContentType(contentType.orNull)
      .setUserAgent(context.useragent.orNull)
      .setBody(body.orNull)
      .setNetworkUserId(context.userId.map(_.toString).orNull)
      .setHeaders(context.headers.asJava)
      .setPath(api.toRaw)
  }

  /**
   * Transform back to array of bytes coming from collector topic
   * Used for tests and debugging
   */
  def toRaw: Array[Byte] =
    CollectorPayload.serializer.serialize(toThrift)
}

object CollectorPayload {

  /** Latest payload SchemaKey */
  val IgluUri: SchemaKey = SchemaKey("com.snowplowanalytics.snowplow", "CollectorPayload", "thrift", SchemaVer.Full(1, 0, 0))

  /**
   * Unambiguously identifies the collector source of this input line.
   * @param name kind and version of the collector (e.g. ssc-1.0.1-kafka)
   * @param encoding usually "UTF-8"
   * @param hostname the actual host the collector was running on
   */
  final case class Source(
    name: String,
    encoding: String,
    hostname: Option[String]
  )

  /**
   * Information *derived* by the collector to be used as meta-data (meta-payload)
   * Everything else in [[CollectorPayload]] is directly payload (body and queryparams)
   * @param timestamp collector_tstamp (not optional in fact)
   * @param ipAddress client's IP address, can be later overwritten by `ip` param in
   *                  `enrichments.Transform`
   * @param useragent UA header, can be later overwritten by `ua` param in `entichments.Transform`
   * @param refererUri extracted from corresponding HTTP header
   * @param headers all headers, including UA and referer URI
   * @param userId generated by collector-set third-party cookie
   */
  final case class Context(
    timestamp: Option[DateTime],
    ipAddress: Option[String],
    useragent: Option[String],
    refererUri: Option[String],
    headers: List[String],
    userId: Option[UUID]
  )

  /**
   * Define the vendor and version of the payload, defined by collector endpoint
   * Coming from [[com.snowplowanalytics.snowplow.enrich.common.adapters.AdapterRegistry]]
   */
  final case class Api(vendor: String, version: String) {

    /** Reverse back to collector's endpoint */
    def toRaw: String = if (this == SnowplowTp1) "/i" else s"$vendor/$version"
  }

  /** Defaults for the tracker vendor and version before we implemented this into Snowplow */
  val SnowplowTp1: Api = Api("com.snowplowanalytics.snowplow", "tp1")

  // To extract the API vendor and version from the the path to the requested object.
  private val ApiPathRegex = """^[/]?([^/]+)/([^/]+)[/]?$""".r

  /**
   * Parses the requested URI path to determine the specific API version this payload follows.
   * @param path The request path
   * @return either a CollectorApi or a Failure String.
   */
  def parseApi(path: String): Either[FailureDetails.CPFormatViolationMessage, Api] =
    path match {
      case ApiPathRegex(vnd, ver) => Api(vnd, ver).asRight
      case _ if isIceRequest(path) => SnowplowTp1.asRight
      case _ =>
        val msg = "path does not match (/)vendor/version(/) nor is a legacy /i(ce.png) request"
        FailureDetails.CPFormatViolationMessage
          .InputData("path", path.some, msg)
          .asLeft
    }

  /**
   * Checks whether a request to a collector is a tracker hitting the ice pixel.
   * @param path The request path
   * @return true if this is a request for the ice pixel
   */
  protected[loaders] def isIceRequest(path: String): Boolean =
    path.startsWith("/ice.png") || // Legacy name for /i
      path.equals("/i") || // Legacy name for /com.snowplowanalytics.snowplow/tp1
      path.startsWith("/i?")

  /** Thrift serializer, used for tests and debugging with `toThrift` */
  private[loaders] lazy val serializer = new TSerializer()
}
