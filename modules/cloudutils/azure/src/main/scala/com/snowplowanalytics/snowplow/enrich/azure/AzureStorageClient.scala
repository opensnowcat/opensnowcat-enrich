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
package com.snowplowanalytics.snowplow.enrich.azure

import java.net.URI

import com.azure.identity.DefaultAzureCredentialBuilder
import com.azure.storage.blob.{BlobServiceAsyncClient, BlobServiceClientBuilder, BlobUrlParts}

import blobstore.azure.AzureStore
import blobstore.url.exception.{MultipleUrlValidationException, Throwables}
import blobstore.url.{Authority, Path, Url}

import cats.data.Validated.{Invalid, Valid}
import cats.implicits._

import cats.effect.kernel.{Async, Resource, Sync}

import fs2.Stream

import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.BlobStorageClients.AzureStorage
import com.snowplowanalytics.snowplow.enrich.common.fs2.io.Clients.Client

object AzureStorageClient {

  def mk[F[_]: Async](config: AzureStorage): Resource[F, Client[F]] =
    for {
      stores <- createStores(config)
    } yield new Client[F] {
      def canDownload(uri: URI): Boolean =
        uri.toString.contains("core.windows.net")

      def download(uri: URI): Stream[F, Byte] = {
        val inputParts = BlobUrlParts.parse(uri.toString)
        stores.get(inputParts.getAccountName) match {
          case None =>
            Stream.raiseError[F](new Exception(s"AzureStore for storage account name '${inputParts.getAccountName}' isn't found"))
          case Some(store) =>
            Authority
              .parse(inputParts.getBlobContainerName)
              .map(authority => Url(inputParts.getScheme, authority, Path(inputParts.getBlobName))) match {
              case Valid(url) => store.get(url, 16 * 1024)
              case Invalid(errors) => Stream.raiseError[F](MultipleUrlValidationException(errors))
            }
        }
      }
    }

  private def createStores[F[_]: Async](config: AzureStorage): Resource[F, Map[String, AzureStore[F]]] =
    config.accounts
      .map { account =>
        createStore(account).map(store => (account.name, store))
      }
      .sequence
      .map(_.toMap)

  private def createStore[F[_]: Async](account: AzureStorage.Account): Resource[F, AzureStore[F]] =
    for {
      client <- createClient(account)
      store <- AzureStore
        .builder[F](client)
        .build
        .fold(
          errors => Resource.eval(Sync[F].raiseError(errors.reduce(Throwables.collapsingSemigroup))),
          s => Resource.pure[F, AzureStore[F]](s)
        )
    } yield store

  private def createClient[F[_]: Sync](account: AzureStorage.Account): Resource[F, BlobServiceAsyncClient] =
    Resource.eval {
      Sync[F].delay {
        createClientBuilder(account)
          .endpoint(createStorageEndpoint(account.name))
          .buildAsyncClient()
      }
    }

  private def createClientBuilder(account: AzureStorage.Account): BlobServiceClientBuilder = {
    val builder = new BlobServiceClientBuilder()
    account.auth match {
      case Some(AzureStorage.Account.Auth.DefaultCredentialsChain) =>
        builder.credential(new DefaultAzureCredentialBuilder().build)
      case Some(AzureStorage.Account.Auth.SasToken(tokenValue)) =>
        builder.sasToken(tokenValue)
      case None =>
        builder
    }
  }

  private def createStorageEndpoint(storageAccountName: String): String =
    s"https://$storageAccountName.blob.core.windows.net"
}
