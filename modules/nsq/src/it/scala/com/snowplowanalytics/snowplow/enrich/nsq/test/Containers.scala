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

package com.snowplowanalytics.snowplow.enrich.nsq.test

import org.slf4j.LoggerFactory

import cats.implicits._

import cats.effect.{Resource, Sync, Async, ContextShift, Blocker}

import org.http4s.client.{JavaNetClientBuilder, Client => Http4sClient}
import org.http4s.{Request ,Method, Uri}

import org.testcontainers.containers.{BindMode, GenericContainer => JGenericContainer, Network}
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.output.Slf4jLogConsumer

import com.dimafeng.testcontainers.{FixedHostPortGenericContainer, GenericContainer}

import com.snowplowanalytics.snowplow.enrich.nsq.generated.BuildInfo

object Containers {

  case class NetworkInfo(
    networkAlias: String,
    broadcastAddress: String,
    httpPort: Int,
    tcpPort: Int
  )

  case class NetworkTopology(
    lookup1: NetworkInfo,
    nsqd1: NetworkInfo,
    lookup2: NetworkInfo,
    nsqd2: NetworkInfo,
    sourceTopic: String,
    goodDestTopic: String,
    badDestTopic: String
  )

  /**
   * Integration test tries to access nsqd from outside of docker network
   * therefore it can't access it with docker service name.
   * Since nsqd broadcasts its address through nsqlookup, it is impossible to
   * broadcast its address as localhost and docker service name at the same time
   * therefore we've created second nsqd and nsqlookup instances that will be used by
   * integration tests and NSQ messages sent to first nsqd instance will be replicated
   * to second nsqd instance with nsq_to_nsq tool.
   */
  def createContainers[F[_]: Async: ContextShift](blocker: Blocker): Resource[F, NetworkTopology] =
    for {
      network <- network()
      topology = NetworkTopology(
        lookup1 = NetworkInfo(networkAlias = "nsqlookupd", broadcastAddress = "nsqlookupd", httpPort = 4161, tcpPort = 4160),
        lookup2 = NetworkInfo(networkAlias = "nsqlookupd2", broadcastAddress = "nsqlookupd2", httpPort = 4261, tcpPort = 4260),
        nsqd1 = NetworkInfo(networkAlias = "nsqd", broadcastAddress = "nsqd", httpPort = 4151, tcpPort = 4150),
        nsqd2 = NetworkInfo(networkAlias = "nsqd2", broadcastAddress = "127.0.0.1", httpPort = 4251, tcpPort = 4250),
        sourceTopic = "RawEvents",
        goodDestTopic = "EnrichedEvents",
        badDestTopic = "BadEnrichedEvents"
      )
      _ <- nsqlookupd(network, topology.lookup1)
      _ <- nsqlookupd(network, topology.lookup2)
      _ <- nsqd(
          network,
          topology.nsqd1,
          lookupAddress = s"${topology.lookup1.networkAlias}:${topology.lookup1.tcpPort}"
        )
      _ <- nsqd(
          network,
          topology.nsqd2,
          lookupAddress = s"${topology.lookup2.networkAlias}:${topology.lookup2.tcpPort}"
        )
      _ <- Resource.eval(createTopics[F](blocker, topology))
      _ <- nsqToNsq(
          network,
          sourceAddress = s"${topology.nsqd1.networkAlias}:${topology.nsqd1.tcpPort}",
          destinationAddress = s"${topology.nsqd2.networkAlias}:${topology.nsqd2.tcpPort}",
          sourceTopic = topology.goodDestTopic,
          destinationTopic = topology.goodDestTopic
        )
      _ <- nsqToNsq(
          network,
          sourceAddress = s"${topology.nsqd1.networkAlias}:${topology.nsqd1.tcpPort}",
          destinationAddress = s"${topology.nsqd2.networkAlias}:${topology.nsqd2.tcpPort}",
          sourceTopic = topology.badDestTopic,
          destinationTopic = topology.badDestTopic
        )
      _ <- enrich(network, topology)
    } yield topology

  private def createTopics[F[_] : Async : ContextShift](blocker: Blocker, topology: NetworkTopology): F[Unit] = {
    val client = JavaNetClientBuilder[F](blocker).create
    for {
      _ <- createTopic(client, topology.sourceTopic, 4151)
      _ <- createTopic(client, topology.goodDestTopic, 4151)
      _ <- createTopic(client, topology.badDestTopic, 4151)
      _ <- createTopic(client, topology.goodDestTopic, 4251)
      _ <- createTopic(client, topology.badDestTopic, 4251)
    } yield ()
  }

  private def createTopic[F[_] : Async : ContextShift](client: Http4sClient[F], topic: String, port: Int): F[Unit] = {
    val request = Request[F](
      method = Method.POST,
      Uri.unsafeFromString(s"http://127.0.0.1:$port/topic/create?topic=$topic")
    )
    client.expect(request)
  }

  private def network[F[_]: Sync](): Resource[F, Network] =
    Resource.make(
      Sync[F].delay {
        Network.newNetwork()
      }
    )(
      n => Sync[F].delay(n.close())
    )

  private def nsqlookupd[F[_]: Sync](
    network: Network,
    networkInfo: NetworkInfo
  ): Resource[F, JGenericContainer[_]] =
    Resource.make (
      Sync[F].delay {
        val container = FixedHostPortGenericContainer(
          imageName = "nsqio/nsq:latest",
          command = Seq(
            "/nsqlookupd",
            s"--broadcast-address=${networkInfo.broadcastAddress}",
            s"--http-address=0.0.0.0:${networkInfo.httpPort}",
            s"--tcp-address=0.0.0.0:${networkInfo.tcpPort}",
          ),
          exposedPorts = List(networkInfo.httpPort, networkInfo.tcpPort),
          exposedContainerPort = networkInfo.httpPort,
          exposedHostPort = networkInfo.httpPort
        )
        container.container.withFixedExposedPort(networkInfo.tcpPort, networkInfo.tcpPort)
        container.container.withNetwork(network)
        container.container.withNetworkAliases(networkInfo.networkAlias)
        startContainerWithLogs(container.container, "nsqlookupd")
      }
    )(
      e => Sync[F].delay(e.stop())
    )

  private def nsqd[F[_] : Sync](
    network: Network,
    networkInfo: NetworkInfo,
    lookupAddress: String
  ): Resource[F, JGenericContainer[_]] =
    Resource.make(
      Sync[F].delay {
        val container = FixedHostPortGenericContainer(
          imageName = "nsqio/nsq:latest",
          command = Seq(
            "/nsqd",
            s"--broadcast-address=${networkInfo.broadcastAddress}",
            s"--broadcast-http-port=${networkInfo.httpPort}",
            s"--broadcast-tcp-port=${networkInfo.tcpPort}",
            s"--http-address=0.0.0.0:${networkInfo.httpPort}",
            s"--tcp-address=0.0.0.0:${networkInfo.tcpPort}",
            s"--lookupd-tcp-address=$lookupAddress"
          ),
          exposedPorts = List(networkInfo.httpPort, networkInfo.tcpPort),
          exposedContainerPort = networkInfo.httpPort,
          exposedHostPort = networkInfo.httpPort
        )
        container.container.withFixedExposedPort(networkInfo.tcpPort, networkInfo.tcpPort)
        container.container.withNetwork(network)
        container.container.withNetworkAliases(networkInfo.networkAlias)
        startContainerWithLogs(container.container, "nsqd")
      }
    )(
      e => Sync[F].delay(e.stop())
    )

  private def nsqToNsq[F[_] : Sync](
    network: Network,
    sourceAddress: String,
    sourceTopic: String,
    destinationAddress: String,
    destinationTopic: String
  ): Resource[F, JGenericContainer[_]] =
    Resource.make(
      Sync[F].delay {
        val container = GenericContainer(
          dockerImage = "nsqio/nsq:latest",
          command = Seq(
            "/nsq_to_nsq",
            s"--nsqd-tcp-address=$sourceAddress",
            s"--topic=$sourceTopic",
            s"--destination-nsqd-tcp-address=$destinationAddress",
            s"--destination-topic=$destinationTopic",
          ),
        )
        container.container.withNetwork(network)
        startContainerWithLogs(container.container, "nsq_to_nsq")
      }
    )(
      e => Sync[F].delay(e.stop())
    )

  private def enrich[F[_] : Sync](
    network: Network,
    topology: NetworkTopology
  ): Resource[F, JGenericContainer[_]] =
    Resource.make(
      Sync[F].delay {
        val container = GenericContainer(
          dockerImage = s"snowplow/snowplow-enrich-nsq:${BuildInfo.version}-distroless",
          env = Map(
            "JDK_JAVA_OPTIONS" -> "-Dorg.slf4j.simpleLogger.defaultLogLevel=info",
            "INPUT_TOPIC" -> topology.sourceTopic,
            "LOOKUP_HOST" -> topology.lookup1.networkAlias,
            "LOOKUP_PORT" -> topology.lookup1.httpPort.toString,
            "GOOD_OUTPUT_TOPIC" -> topology.goodDestTopic,
            "BAD_OUTPUT_TOPIC" -> topology.badDestTopic,
            "NSQD_HOST" -> topology.nsqd1.networkAlias,
            "NSQD_PORT" -> topology.nsqd1.tcpPort.toString
          ),
          fileSystemBind = Seq(
            GenericContainer.FileSystemBind(
              "modules/nsq/src/it/resources/enrich/enrich-nsq.hocon",
              "/snowplow/config/enrich-nsq.hocon",
              BindMode.READ_ONLY
            ),
            GenericContainer.FileSystemBind(
              "modules/nsq/src/it/resources/enrich/iglu_resolver.json",
              "/snowplow/config/iglu_resolver.json",
              BindMode.READ_ONLY
            ),
            GenericContainer.FileSystemBind(
              "modules/nsq/src/it/resources/enrich/enrichments",
              "/snowplow/config/enrichments",
              BindMode.READ_ONLY
            )
          ),
          command = Seq(
            "--config",
            "/snowplow/config/enrich-nsq.hocon",
            "--iglu-config",
            "/snowplow/config/iglu_resolver.json",
            "--enrichments",
            "/snowplow/config/enrichments"
          ),
          waitStrategy = Wait.forLogMessage(s".*Running Enrich.*", 1)
        )
        container.container.withNetwork(network)
        startContainerWithLogs(container.container, "enrich")
      }
    )(
      e => Sync[F].delay(e.stop())
    )

  private def startContainerWithLogs(
    container: JGenericContainer[_],
    loggerName: String
  ): JGenericContainer[_] = {
    val logger = LoggerFactory.getLogger(loggerName)
    val logs = new Slf4jLogConsumer(logger)
    container.start()
    container.followOutput(logs)
    container
  }
}
