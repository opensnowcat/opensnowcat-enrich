/*
 * Copyright (c) 2023 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.gcp

import java.net.URI

import cats.implicits._
import cats.effect.{Async, Sync}

import fs2.Stream

import blobstore.gcs.GcsStore
import blobstore.url.Url

import com.google.cloud.storage.StorageOptions
import com.google.cloud.BaseServiceException

import com.snowplowanalytics.snowplow.enrich.common.fs2.io.Clients.{Client, RetryableFailure}

object GcsClient {

  def mk[F[_]: Async]: F[Client[F]] =
    Sync[F].delay(StorageOptions.getDefaultInstance.getService).map { service =>
      new Client[F] {
        val store = GcsStore.builder(service).unsafe

        def canDownload(uri: URI): Boolean = uri.getScheme == "gs"

        def download(uri: URI): Stream[F, Byte] =
          Stream.eval(Url.parseF[F](uri.toString)).flatMap { url =>
            store
              .get(url, 16 * 1024)
              .handleErrorWith { e =>
                val e2 = e match {
                  case bse: BaseServiceException if bse.isRetryable =>
                    new RetryableFailure {
                      override def getMessage: String = bse.getMessage
                      override def getCause: Throwable = bse
                    }
                  case e => e
                }
                Stream.raiseError[F](e2)
              }
          }
      }
    }
}
