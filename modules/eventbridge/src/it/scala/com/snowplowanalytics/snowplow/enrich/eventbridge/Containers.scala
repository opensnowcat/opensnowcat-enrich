/*
 * Copyright (c) 2022-2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.enrich.eventbridge

import java.util.UUID

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import org.slf4j.LoggerFactory

import retry.syntax.all._
import retry.RetryPolicies

import cats.syntax.flatMap._

import cats.effect.{IO, Resource, Timer}

import org.testcontainers.containers.{BindMode, GenericContainer => JGenericContainer, Network}
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.output.Slf4jLogConsumer

import com.dimafeng.testcontainers.GenericContainer

import com.snowplowanalytics.snowplow.enrich.eventbridge.enrichments.{Enrichment, Enrichments}
import com.snowplowanalytics.snowplow.enrich.eventbridge.generated.BuildInfo

object Containers {

  private val executionContext: ExecutionContext = ExecutionContext.global
  implicit val ioTimer: Timer[IO] = IO.timer(executionContext)

  private val network = Network.newNetwork()

  private val localstackPort = 4566
  private val localstackAlias = "localstack"

  val localstack = {
    val container = GenericContainer(
      dockerImage = "localstack/localstack:2.1.0",
      fileSystemBind = Seq(
        GenericContainer.FileSystemBind(
          "modules/eventbridge/src/it/resources/localstack",
          "/docker-entrypoint-initaws.d",
          BindMode.READ_ONLY
        )
      ),
      env = Map(
        "AWS_ACCESS_KEY_ID" -> "foo",
        "AWS_SECRET_ACCESS_KEY" -> "bar"
      ),
      waitStrategy = Wait.forLogMessage(".*Ready.*", 1),
      exposedPorts = Seq(localstackPort)
    )
    container.underlyingUnsafeContainer.withNetwork(network)
    container.underlyingUnsafeContainer.withNetworkAliases(localstackAlias)
    container.container
  }

  def localstackMappedPort = localstack.getMappedPort(localstackPort)

  def enrich(
    configPath: String,
    testName: String,
    needsLocalstack: Boolean,
    enrichments: List[Enrichment],
    uuid: String = UUID.randomUUID().toString,
    waitLogMessage: String = "Running Enrich"
  ): Resource[IO, JGenericContainer[_]] = {
    val streams = IntegrationTestConfig.getStreams(uuid)

    val container = GenericContainer(
      dockerImage = s"snowplow/snowplow-enrich-eventbridge:${BuildInfo.version}-distroless",
      env = Map(
        "AWS_REGION" -> IntegrationTestConfig.region,
        "AWS_ACCESS_KEY_ID" -> "foo",
        "AWS_SECRET_ACCESS_KEY" -> "bar",
        "JDK_JAVA_OPTIONS" -> "-Dorg.slf4j.simpleLogger.defaultLogLevel=info",
        // appName must be unique in enrich config so that Kinesis consumers in tests don't interfere
        "APP_NAME" -> s"${testName}_$uuid",
        "REGION" -> IntegrationTestConfig.region,
        "KINESIS_STREAM_INPUT" -> streams.kinesisInput,
        "EVENTBRIDGE_STREAM_OUTPUT_GOOD_EVENTBUS_NAME" -> streams.eventbridgeGood.eventBusName,
        "EVENTBRIDGE_STREAM_OUTPUT_GOOD_EVENTBUS_SOURCE" -> streams.eventbridgeGood.eventBusSource,
        "EVENTBRIDGE_STREAM_OUTPUT_BAD_EVENTBUS_NAME" -> streams.eventbridgeBad.eventBusName,
        "EVENTBRIDGE_STREAM_OUTPUT_BAD_EVENTBUS_SOURCE" -> streams.eventbridgeBad.eventBusSource,
        "KINESIS_STREAM_OUTPUT_GOOD" -> streams.kinesisOutputGood,
        "KINESIS_STREAM_OUTPUT_BAD" -> streams.kinesisOutputBad,
        "LOCALSTACK_ENDPOINT" -> s"http://$localstackAlias:$localstackPort"
      ),
      fileSystemBind = Seq(
        GenericContainer.FileSystemBind(
          configPath,
          "/snowplow/config/enrich.hocon",
          BindMode.READ_ONLY
        ),
        GenericContainer.FileSystemBind(
          "modules/eventbridge/src/it/resources/enrich/iglu_resolver.json",
          "/snowplow/config/iglu_resolver.json",
          BindMode.READ_ONLY
        )
      ),
      command = Seq(
        "--config",
        "/snowplow/config/enrich.hocon",
        "--iglu-config",
        "/snowplow/config/iglu_resolver.json",
        "--enrichments",
        Enrichments.mkJson(enrichments.map(_.config))
      ),
      waitStrategy = Wait.forLogMessage(s".*$waitLogMessage.*", 1)
    )
    container.container.withNetwork(network)
    Resource.make(
      IO(startLocalstack(needsLocalstack, IntegrationTestConfig.region, streams)) >>
        IO(startContainerWithLogs(container.container, testName))
    )(e => IO(e.stop()))
  }

  def mysqlServer: Resource[IO, JGenericContainer[_]] =
    Resource.make {
      val container = GenericContainer(
        dockerImage = "mysql:8.0.31",
        fileSystemBind = Seq(
          GenericContainer.FileSystemBind(
            "modules/eventbridge/src/it/resources/mysql",
            "/docker-entrypoint-initdb.d",
            BindMode.READ_ONLY
          )
        ),
        env = Map(
          "MYSQL_RANDOM_ROOT_PASSWORD" -> "yes",
          "MYSQL_DATABASE" -> "snowplow",
          "MYSQL_USER" -> "enricher",
          "MYSQL_PASSWORD" -> "supersecret1"
        ),
        waitStrategy = Wait.forLogMessage(".*ready for connections.*", 1)
      )
      container.underlyingUnsafeContainer.withNetwork(network)
      container.underlyingUnsafeContainer.withNetworkAliases("mysql")
      IO(container.start()) >> IO.pure(container.container)
    } { c =>
      IO(c.stop())
    }

  def httpServer: Resource[IO, JGenericContainer[_]] =
    Resource.make {
      val container = GenericContainer(
        dockerImage = "nginx:1.23.2",
        fileSystemBind = Seq(
          GenericContainer.FileSystemBind(
            "modules/eventbridge/src/it/resources/nginx/default.conf",
            "/etc/nginx/conf.d/default.conf",
            BindMode.READ_ONLY
          ),
          GenericContainer.FileSystemBind(
            "modules/eventbridge/src/it/resources/nginx/.htpasswd",
            "/etc/.htpasswd",
            BindMode.READ_ONLY
          ),
          GenericContainer.FileSystemBind(
            "modules/eventbridge/src/it/resources/nginx/www",
            "/usr/share/nginx/html",
            BindMode.READ_ONLY
          )
        ),
        waitStrategy = Wait.forLogMessage(".*start worker processes.*", 1)
      )
      container.underlyingUnsafeContainer.withNetwork(network)
      container.underlyingUnsafeContainer.withNetworkAliases("api")
      IO(container.start()) >> IO.pure(container.container)
    } { c =>
      IO(c.stop())
    }

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

  def waitUntilStopped(container: JGenericContainer[_]): IO[Boolean] = {
    val retryPolicy = RetryPolicies.limitRetriesByCumulativeDelay(
      5.minutes,
      RetryPolicies.capDelay[IO](
        2.second,
        RetryPolicies.fullJitter[IO](1.second)
      )
    )

    IO(container.isRunning()).retryingOnFailures(
      _ == false,
      retryPolicy,
      (_, _) => IO.unit
    )
  }

  // synchronized so that start() isn't called by several threads at the same time.
  // start() is blocking.
  // Calling start() on an already started container has no effect.
  private def startLocalstack(
    needsLocalstack: Boolean,
    region: String,
    streams: IntegrationTestConfig.Streams
  ): Unit =
    synchronized {
      if (needsLocalstack) {
        localstack.start()
        createStreams(
          localstack.getMappedPort(localstackPort),
          region,
          streams
        )
      } else ()
    }

  private def createStreams(
    localstackPort: Int,
    region: String,
    streams: IntegrationTestConfig.Streams
  ): Unit = {
    import software.amazon.awssdk.services._

    val endpoint = java.net.URI.create(s"http://127.0.0.4:$localstackPort")
    val eventBridgeClient = eventbridge.EventBridgeClient
            .builder()
            .region(software.amazon.awssdk.regions.Region.of(region))
            .endpointOverride(endpoint)
            .build()

    val kinesisClient = kinesis.KinesisClient
            .builder()
            .region(software.amazon.awssdk.regions.Region.of(region))
            .endpointOverride(java.net.URI.create(s"http://127.0.0.4:$localstackMappedPort"))
            .build()

    // kinesis streams
    val kinesisStreamNames = List(streams.kinesisInput, streams.kinesisOutputGood, streams.kinesisOutputBad)
    kinesisStreamNames.foreach { stream =>
      kinesisClient.createStream(kinesis.model.CreateStreamRequest.builder.streamName(stream).shardCount(1).build)
    }

    def getKinesisARN(streamName: String): String = {
      kinesisClient.describeStream(kinesis.model.DescribeStreamRequest.builder.streamName(streamName).build()).streamDescription().streamARN()
    }

    val eventbridgeStreams = List(
      streams.eventbridgeGood -> getKinesisARN(streams.kinesisOutputGood),
      streams.eventbridgeBad -> getKinesisARN(streams.kinesisOutputBad))

    eventbridgeStreams.foreach { case (eventbridgeStream, kinesisStreamARN) =>
      // event-bus
      eventBridgeClient.createEventBus(eventbridge.model.CreateEventBusRequest.builder()
        .name(eventbridgeStream.eventBusName)
        .build()
      )

      // rule
      val ruleName = s"rule-${eventbridgeStream.eventBusName}"
      val eventPattern = s"""{ "source": ["${eventbridgeStream.eventBusSource}"] }"""//.replace("\"", "\\\"")
      eventBridgeClient.putRule(eventbridge.model.PutRuleRequest.builder()
        .eventBusName(eventbridgeStream.eventBusName)
        .name(ruleName)
        .eventPattern(eventPattern)
        .build()
      )

      // target
      val targetId = s"${eventbridgeStream.eventBusName}-target".take(64)
      val targetCreationResult = eventBridgeClient.putTargets(eventbridge.model.PutTargetsRequest.builder()
        .eventBusName(eventbridgeStream.eventBusName)
        .rule(ruleName)
        .targets(eventbridge.model.Target.builder().id(targetId).arn(kinesisStreamARN).build())
        .build()
      )
      assert(targetCreationResult.failedEntryCount == 0, s"Target creation failed: $targetCreationResult")
    }
  }
}
