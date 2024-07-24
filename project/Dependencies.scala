/*
 * Copyright (c) 2012-2023 Snowplow Analytics Ltd. All rights reserved.
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
// =======================================================
// scalafmt: {align.tokens = ["%", "%%"]}
// =======================================================
import sbt._

object Dependencies {

  val resolutionRepos = Seq(
    // For some Twitter libs and uaParser utils
    "Concurrent Maven Repo" at "https://conjars.org/repo",
    // For Twitter's util functions
    "Twitter Maven Repo" at "https://maven.twttr.com/",
    // For legacy Snowplow libs
    ("Snowplow Analytics Maven repo" at "http://maven.snplow.com/releases/").withAllowInsecureProtocol(true),
    // For Confluent libs
    ("Confluent Repository" at "https://packages.confluent.io/maven/")
  )

  object V {
    // Java
    val commonsCodec = "1.16.0"
    val commonsText = "1.12.0"
    val jodaTime = "2.12.7"
    val useragent = "1.21"
    val uaParser = "1.6.1"
    val snakeYaml = "2.2"
    val postgresDriver = "42.7.3"
    val mysqlConnector = "8.3.0"
    val hikariCP = "5.1.0"
    val jaywayJsonpath = "2.7.0"
    val jsonsmart = "2.5.1"
    val iabClient = "0.2.0"
    val yauaa = "7.24.0"
    val log4jToSlf4j = "2.23.1"
    val guava = "33.2.1-jre"
    val slf4j = "2.0.13"
    val log4j = "2.23.1"
    val thrift = "0.20.0"
    val sprayJson = "1.3.6"
    val netty = "4.1.112.Final"
    val protobuf = "4.27.2"

    val refererParser = "1.1.0"
    val maxmindIplookups = "0.7.2"
    val circe = "0.14.1"
    val circeOptics = "0.14.1"
    val circeConfig = "0.7.0"
    val circeJackson = "0.14.0"
    val scalaForex = "1.0.0"
    val scalaWeather = "1.0.0"
    val gatlingJsonpath = "0.6.14"
    val scalaUri = "4.0.3"
    val badRows = "2.1.2"
    val igluClient = "1.5.0"

    val snowplowRawEvent = "0.1.0"
    val collectorPayload = "0.0.0"
    val schemaSniffer = "0.0.0"

    val awsSdk = "1.12.762"
    val gcpSdk = "2.31.0"
    val awsSdk2 = "2.25.69"
    val kinesisClient2 = "2.4.3"
    val kafka = "7.6.1-ce"
    val mskAuth = "2.2.0"
    val nsqClient = "1.3.0"
    val jackson = "2.15.3"
    val config = "1.3.4"

    val decline = "2.4.1"
    val fs2 = "2.5.11"
    val catsEffect = "2.5.5"
    val fs2PubSub = "0.18.1"
    val fs2Aws = "3.1.1"
    val fs2Kafka = "1.10.0"
    val fs2BlobStorage = "0.8.11"
    val azureIdentity = "1.11.0"
    val http4s = "0.21.34"
    val log4cats = "1.7.0"
    val catsRetry = "2.1.1"
    val specsDiff = "0.9.0"
    val eventGen = "0.2.2"

    val snowplowTracker = "1.0.0"

    val specs2 = "4.20.8"
    val specs2Cats = "4.11.0"
    val specs2CE = "0.5.4"
    val scalacheck = "1.18.0"
    val testcontainers = "0.40.10"
    val parserCombinators = "2.4.0"
    val sentry = "1.7.30"

    val betterMonadicFor = "0.3.1"
  }

  object Libraries {
    val commonsCodec = "commons-codec"                 % "commons-codec"     % V.commonsCodec
    val commonsText = "org.apache.commons"             % "commons-text"      % V.commonsText
    val jodaTime = "joda-time"                         % "joda-time"         % V.jodaTime
    val useragent = "eu.bitwalker"                     % "UserAgentUtils"    % V.useragent
    val jacksonDatabind = "com.fasterxml.jackson.core" % "jackson-databind"  % V.jackson
    val snakeYaml = "org.yaml"                         % "snakeyaml"         % V.snakeYaml
    val uaParser = "com.github.ua-parser"              % "uap-java"          % V.uaParser
    val postgresDriver = "org.postgresql"              % "postgresql"        % V.postgresDriver
    val mysqlConnector = "com.mysql"                   % "mysql-connector-j" % V.mysqlConnector
    val hikariCP = ("com.zaxxer"                       % "HikariCP"          % V.hikariCP)
      .exclude("org.slf4j", "slf4j-api")
    val jaywayJsonpath = "com.jayway.jsonpath"    % "json-path"      % V.jaywayJsonpath
    val jsonsmart = "net.minidev"                 % "json-smart"     % V.jsonsmart
    val yauaa = "nl.basjes.parse.useragent"       % "yauaa"          % V.yauaa
    val log4jToSlf4j = "org.apache.logging.log4j" % "log4j-to-slf4j" % V.log4jToSlf4j
    val log4j = "org.apache.logging.log4j"        % "log4j-core"     % V.log4j
    val guava = "com.google.guava"                % "guava"          % V.guava

    val circeCore = "io.circe"                     %% "circe-core"                    % V.circe
    val circeGeneric = "io.circe"                  %% "circe-generic"                 % V.circe
    val circeExtras = "io.circe"                   %% "circe-generic-extras"          % V.circe
    val circeParser = "io.circe"                   %% "circe-parser"                  % V.circe
    val circeLiteral = "io.circe"                  %% "circe-literal"                 % V.circe
    val circeConfig = "io.circe"                   %% "circe-config"                  % V.circeConfig
    val circeOptics = "io.circe"                   %% "circe-optics"                  % V.circeOptics
    val circeJackson = "io.circe"                  %% "circe-jackson210"              % V.circeJackson
    val scalaUri = "io.lemonlabs"                  %% "scala-uri"                     % V.scalaUri
    val gatlingJsonpath = "io.gatling"             %% "jsonpath"                      % V.gatlingJsonpath
    val scalaForex = "com.snowplowanalytics"       %% "scala-forex"                   % V.scalaForex
    val refererParser = "com.snowplowanalytics"    %% "scala-referer-parser"          % V.refererParser
    val maxmindIplookups = "com.snowplowanalytics" %% "scala-maxmind-iplookups"       % V.maxmindIplookups
    val scalaWeather = "com.snowplowanalytics"     %% "scala-weather"                 % V.scalaWeather
    val badRows = "com.snowplowanalytics"          %% "snowplow-badrows"              % V.badRows
    val igluClient = "com.snowplowanalytics"       %% "iglu-scala-client"             % V.igluClient
    val igluClientHttp4s = "com.snowplowanalytics" %% "iglu-scala-client-http4s"      % V.igluClient
    val snowplowRawEvent = "com.snowplowanalytics"  % "snowplow-thrift-raw-event"     % V.snowplowRawEvent
    val collectorPayload = "com.snowplowanalytics"  % "collector-payload-1"           % V.collectorPayload
    val schemaSniffer = "com.snowplowanalytics"     % "schema-sniffer-1"              % V.schemaSniffer
    val iabClient = "com.snowplowanalytics"         % "iab-spiders-and-robots-client" % V.iabClient
    val thrift = "org.apache.thrift"                % "libthrift"                     % V.thrift
    val sprayJson = "io.spray"                     %% "spray-json"                    % V.sprayJson
    val nettyAll = "io.netty"                       % "netty-all"                     % V.netty
    val nettyCodec = "io.netty"                     % "netty-codec"                   % V.netty
    val slf4j = "org.slf4j"                         % "slf4j-simple"                  % V.slf4j
    val sentry = "io.sentry"                        % "sentry"                        % V.sentry
    val protobuf = "com.google.protobuf"            % "protobuf-java"                 % V.protobuf

    val specs2 = "org.specs2"                        %% "specs2-core"                   % V.specs2            % Test
    val specs2Cats = "org.specs2"                    %% "specs2-cats"                   % V.specs2Cats        % Test
    val specs2Scalacheck = "org.specs2"              %% "specs2-scalacheck"             % V.specs2            % Test
    val specs2Mock = "org.specs2"                    %% "specs2-mock"                   % V.specs2            % Test
    val specs2CE = "com.codecommit"                  %% "cats-effect-testing-specs2"    % V.specs2CE          % Test
    val specs2CEIt = "com.codecommit"                %% "cats-effect-testing-specs2"    % V.specs2CE          % IntegrationTest
    val specsDiff = "com.softwaremill.diffx"         %% "diffx-specs2"                  % V.specsDiff         % Test
    val eventGen = "com.snowplowanalytics"           %% "snowplow-event-generator-core" % V.eventGen          % Test
    val parserCombinators = "org.scala-lang.modules" %% "scala-parser-combinators"      % V.parserCombinators % Test
    val testContainersIt = "com.dimafeng"            %% "testcontainers-scala-core"     % V.testcontainers    % IntegrationTest

    val kinesisSdk = "com.amazonaws"        % "aws-java-sdk-kinesis"  % V.awsSdk
    val dynamodbSdk = "com.amazonaws"       % "aws-java-sdk-dynamodb" % V.awsSdk
    val sts = "com.amazonaws"               % "aws-java-sdk-sts"      % V.awsSdk     % Runtime
    val gcs = "com.google.cloud"            % "google-cloud-storage"  % V.gcpSdk
    val kafkaClients = "org.apache.kafka"   % "kafka-clients"         % V.kafka
    val mskAuth = "software.amazon.msk"     % "aws-msk-iam-auth"      % V.mskAuth    % Runtime
    val config = "com.typesafe"             % "config"                % V.config
    val nsqClient = "com.snowplowanalytics" % "nsq-java-client"       % V.nsqClient
    val catsEffect = "org.typelevel"       %% "cats-effect"           % V.catsEffect
    val scalacheck = "org.scalacheck"      %% "scalacheck"            % V.scalacheck % Test

    // FS2
    val decline = "com.monovore"    %% "decline"                % V.decline
    val fs2PubSub = "com.permutive" %% "fs2-google-pubsub-grpc" % V.fs2PubSub
    val fs2Aws = ("io.laserdisc"    %% "fs2-aws"                % V.fs2Aws)
      .exclude("com.amazonaws", "amazon-kinesis-producer")
      .exclude("software.amazon.kinesis", "amazon-kinesis-client")
    val fs2 = "co.fs2"                             %% "fs2-core"              % V.fs2
    val fs2Io = "co.fs2"                           %% "fs2-io"                % V.fs2
    val fs2Kafka = "com.github.fd4s"               %% "fs2-kafka"             % V.fs2Kafka
    val kinesisSdk2 = "software.amazon.awssdk"      % "kinesis"               % V.awsSdk2
    val dynamoDbSdk2 = "software.amazon.awssdk"     % "dynamodb"              % V.awsSdk2
    val s3Sdk2 = "software.amazon.awssdk"           % "s3"                    % V.awsSdk2
    val cloudwatchSdk2 = "software.amazon.awssdk"   % "cloudwatch"            % V.awsSdk2
    val kinesisClient2 = ("software.amazon.kinesis" % "amazon-kinesis-client" % V.kinesisClient2)
      .exclude("software.amazon.glue", "schema-registry-common")
      .exclude("software.amazon.glue", "schema-registry-serde")
    val stsSdk2 = "software.amazon.awssdk"                % "sts"                                   % V.awsSdk2 % Runtime
    val eventbridgeSdk2 = "software.amazon.awssdk"        % "eventbridge"                           % V.awsSdk2
    val azureIdentity = "com.azure"                       % "azure-identity"                        % V.azureIdentity
    val jacksonDfXml = "com.fasterxml.jackson.dataformat" % "jackson-dataformat-xml"                % V.jackson
    val http4sClient = "org.http4s"                      %% "http4s-blaze-client"                   % V.http4s
    val http4sCirce = "org.http4s"                       %% "http4s-circe"                          % V.http4s
    val log4cats = "org.typelevel"                       %% "log4cats-slf4j"                        % V.log4cats
    val catsRetry = "com.github.cb372"                   %% "cats-retry"                            % V.catsRetry
    val fs2BlobS3 = "com.github.fs2-blobstore"           %% "s3"                                    % V.fs2BlobStorage
    val fs2BlobGcs = "com.github.fs2-blobstore"          %% "gcs"                                   % V.fs2BlobStorage
    val fs2BlobAzure = "com.github.fs2-blobstore"        %% "azure"                                 % V.fs2BlobStorage
    val http4sDsl = "org.http4s"                         %% "http4s-dsl"                            % V.http4s  % Test
    val http4sServer = "org.http4s"                      %% "http4s-blaze-server"                   % V.http4s  % Test
    val trackerCore = "com.snowplowanalytics"            %% "snowplow-scala-tracker-core"           % V.snowplowTracker
    val emitterHttps = "com.snowplowanalytics"           %% "snowplow-scala-tracker-emitter-http4s" % V.snowplowTracker

    // compiler plugins
    val betterMonadicFor = "com.olegpy" %% "better-monadic-for" % V.betterMonadicFor

    val commonDependencies = Seq(
      jodaTime,
      commonsCodec,
      commonsText,
      useragent,
      jacksonDatabind,
      uaParser,
      snakeYaml,
      postgresDriver,
      mysqlConnector,
      hikariCP,
      jaywayJsonpath,
      jsonsmart,
      iabClient,
      yauaa,
      log4jToSlf4j,
      guava,
      circeOptics,
      circeJackson,
      refererParser,
      maxmindIplookups,
      scalaUri,
      scalaForex,
      scalaWeather,
      gatlingJsonpath,
      badRows,
      igluClient,
      snowplowRawEvent,
      http4sClient,
      collectorPayload,
      schemaSniffer,
      thrift,
      sprayJson,
      nettyAll,
      nettyCodec,
      protobuf,
      specs2,
      specs2Cats,
      specs2Scalacheck,
      specs2Mock,
      specs2CE,
      circeLiteral % Test,
      parserCombinators
    )

    val commonFs2Dependencies = Seq(
      decline,
      circeExtras,
      circeLiteral,
      circeConfig,
      catsEffect,
      fs2,
      fs2Io,
      slf4j,
      sentry,
      log4cats,
      catsRetry,
      igluClient,
      igluClientHttp4s,
      http4sClient,
      http4sCirce,
      trackerCore,
      emitterHttps,
      specs2,
      specs2CE,
      scalacheck,
      specs2Scalacheck,
      http4sDsl,
      http4sServer,
      eventGen,
      specsDiff,
      circeCore    % Test,
      circeGeneric % Test,
      circeParser  % Test
    )

    val awsUtilsDependencies = Seq(
      fs2BlobS3,
      s3Sdk2
    )

    val gcpUtilsDependencies = Seq(
      fs2BlobGcs,
      gcs
    )

    val azureUtilsDependencies = Seq(
      fs2BlobAzure,
      azureIdentity,
      jacksonDfXml
    )

    val pubsubDependencies = Seq(
      fs2BlobGcs,
      gcs,
      fs2PubSub,
      specs2,
      specs2CE
    )

    val kinesisDependencies = Seq(
      dynamodbSdk,
      kinesisSdk,
      fs2BlobS3,
      fs2Aws,
      kinesisSdk2,
      dynamoDbSdk2,
      s3Sdk2,
      cloudwatchSdk2,
      kinesisClient2,
      stsSdk2,
      sts,
      specs2,
      specs2CE
    )

    val kafkaDependencies = Seq(
      fs2Kafka,
      kafkaClients, // override kafka-clients 2.8.1 from fs2Kafka to address https://security.snyk.io/vuln/SNYK-JAVA-ORGAPACHEKAFKA-3027430
      mskAuth,
      specs2,
      specs2CE
    )

    val nsqDependencies = Seq(
      nsqClient,
      fs2BlobS3,
      fs2BlobGcs,
      log4j, // for security vulnerabilities
      specs2,
      specs2CE
    )

    val eventbridgeDependencies = Seq(
      dynamodbSdk,
      kinesisSdk,
      fs2BlobS3,
      fs2Aws,
      kinesisSdk2,
      dynamoDbSdk2,
      s3Sdk2,
      cloudwatchSdk2,
      kinesisClient2,
      stsSdk2,
      sts,
      specs2,
      specs2CE,
      eventbridgeSdk2
    )

    // exclusions
    val exclusions = Seq(
      "org.apache.tomcat.embed" % "tomcat-embed-core"
    )
  }
}
