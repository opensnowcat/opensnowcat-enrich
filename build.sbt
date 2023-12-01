/*
 * Copyright (c) 2012-2022 Snowplow Analytics Ltd. All rights reserved.
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
// =======================================================
// scalafmt: {align.tokens = [":="]}
// =======================================================
import Dependencies.Libraries._
import BuildSettings._

lazy val root = project.in(file("."))
  .settings(name := "enrich")
  .settings(projectSettings)
  .settings(compilerSettings)
  .settings(resolverSettings)
  .aggregate(common, commonFs2, pubsub, pubsubDistroless, kinesis, kinesisDistroless, streamCommon, streamKinesis, streamKinesisDistroless, streamKafka, streamKafkaDistroless, streamNsq, streamNsqDistroless, streamStdin, kafka, kafkaDistroless, rabbitmq, rabbitmqDistroless, nsq, nsqDistroless, eventbridge, eventbridgeDistroless)

lazy val common = project
  .in(file("modules/common"))
  .settings(commonBuildSettings)
  .settings(libraryDependencies ++= commonDependencies)
  .settings(excludeDependencies ++= exclusions)

lazy val streamCommon = project
  .in(file("modules/stream/common"))
  .enablePlugins(BuildInfoPlugin)
  .settings(streamCommonBuildSettings)
  .settings(libraryDependencies ++= streamCommonDependencies)
  .settings(excludeDependencies ++= exclusions)
  .dependsOn(common % "test->test;compile->compile")

lazy val streamKinesis = project
  .in(file("modules/stream/kinesis"))
  .enablePlugins(JavaAppPackaging, SnowplowDockerPlugin)
  .settings(streamKinesisBuildSettings)
  .settings(libraryDependencies ++= streamKinesisDependencies)
  .settings(excludeDependencies ++= exclusions)
  .dependsOn(streamCommon)

lazy val streamKinesisDistroless = project
  .in(file("modules/distroless/stream/kinesis"))
  .enablePlugins(JavaAppPackaging, SnowplowDistrolessDockerPlugin)
  .settings(sourceDirectory := (streamKinesis / sourceDirectory).value)
  .settings(streamKinesisDistrolessBuildSettings)
  .settings(libraryDependencies ++= streamKinesisDependencies)
  .settings(excludeDependencies ++= exclusions)
  .dependsOn(streamCommon)

lazy val streamKafka = project
  .in(file("modules/stream/kafka"))
  .enablePlugins(JavaAppPackaging, SnowplowDockerPlugin)
  .settings(streamKafkaBuildSettings)
  .settings(libraryDependencies ++= streamKafkaDependencies)
  .settings(excludeDependencies ++= exclusions)
  .dependsOn(streamCommon)

lazy val streamKafkaDistroless = project
  .in(file("modules/distroless/stream/kafka"))
  .enablePlugins(JavaAppPackaging, SnowplowDistrolessDockerPlugin)
  .settings(sourceDirectory := (streamKafka / sourceDirectory).value)
  .settings(streamKafkaDistrolessBuildSettings)
  .settings(libraryDependencies ++= streamKafkaDependencies)
  .settings(excludeDependencies ++= exclusions)
  .dependsOn(streamCommon)

lazy val streamNsq = project
  .in(file("modules/stream/nsq"))
  .enablePlugins(JavaAppPackaging, SnowplowDockerPlugin)
  .settings(streamNsqBuildSettings)
  .settings(libraryDependencies ++= streamNsqDependencies)
  .settings(excludeDependencies ++= exclusions)
  .dependsOn(streamCommon)

lazy val streamNsqDistroless = project
  .in(file("modules/distroless/stream/nsq"))
  .enablePlugins(JavaAppPackaging, SnowplowDistrolessDockerPlugin)
  .settings(sourceDirectory := (streamNsq / sourceDirectory).value)
  .settings(streamNsqDistrolessBuildSettings)
  .settings(libraryDependencies ++= streamNsqDependencies)
  .settings(excludeDependencies ++= exclusions)
  .dependsOn(streamCommon)

lazy val streamStdin = project
  .in(file("modules/stream/stdin"))
  .settings(streamStdinBuildSettings)
  .settings(libraryDependencies ++= streamCommonDependencies)
  .settings(excludeDependencies ++= exclusions)
  .dependsOn(streamCommon)

lazy val commonFs2 = project
  .in(file("modules/common-fs2"))
  .enablePlugins(BuildInfoPlugin)
  .settings(commonFs2BuildSettings)
  .settings(libraryDependencies ++= commonFs2Dependencies)
  .settings(Defaults.itSettings)
  .configs(IntegrationTest)
  .settings(addCompilerPlugin(betterMonadicFor))
  .dependsOn(common % "test->test;compile->compile")


lazy val pubsub = project
  .in(file("modules/pubsub"))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, SnowplowDockerPlugin)
  .settings(pubsubBuildSettings)
  .settings(libraryDependencies ++= pubsubDependencies)
  .settings(excludeDependencies ++= exclusions)
  .settings(addCompilerPlugin(betterMonadicFor))
  .dependsOn(commonFs2)

lazy val pubsubDistroless = project
  .in(file("modules/distroless/pubsub"))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, SnowplowDistrolessDockerPlugin)
  .settings(sourceDirectory := (pubsub / sourceDirectory).value)
  .settings(pubsubDistrolessBuildSettings)
  .settings(libraryDependencies ++= pubsubDependencies)
  .settings(excludeDependencies ++= exclusions)
  .settings(addCompilerPlugin(betterMonadicFor))
  .dependsOn(commonFs2)


lazy val kinesis = project
  .in(file("modules/kinesis"))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, SnowplowDockerPlugin)
  .settings(kinesisBuildSettings)
  .settings(libraryDependencies ++= kinesisDependencies)
  .settings(excludeDependencies ++= exclusions)
  .settings(addCompilerPlugin(betterMonadicFor))
  .dependsOn(commonFs2)

lazy val kinesisDistroless = project
  .in(file("modules/distroless/kinesis"))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, SnowplowDistrolessDockerPlugin)
  .settings(sourceDirectory := (kinesis / sourceDirectory).value)
  .settings(kinesisDistrolessBuildSettings)
  .settings(libraryDependencies ++= kinesisDependencies ++ Seq(
    // integration tests dependencies
    specs2CEIt,
    testContainersIt
  ))
  .settings(excludeDependencies ++= exclusions)
  .settings(addCompilerPlugin(betterMonadicFor))
  .dependsOn(commonFs2 % "compile->compile;it->it")
  .settings(Defaults.itSettings)
  .configs(IntegrationTest)
  .settings((IntegrationTest / test) := (IntegrationTest / test).dependsOn(Docker / publishLocal).value)
  .settings((IntegrationTest / testOnly) := (IntegrationTest / testOnly).dependsOn(Docker / publishLocal).evaluated)

lazy val kafka = project
  .in(file("modules/kafka"))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, SnowplowDockerPlugin)
  .settings(kafkaBuildSettings)
  .settings(libraryDependencies ++= kafkaDependencies ++ Seq(
    // integration tests dependencies
    specs2CEIt
  ))
  .settings(excludeDependencies ++= exclusions)
  .settings(Defaults.itSettings)
  .configs(IntegrationTest)
  .settings(addCompilerPlugin(betterMonadicFor))
  .dependsOn(commonFs2 % "compile->compile;it->it")

lazy val kafkaDistroless = project
  .in(file("modules/distroless/kafka"))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, SnowplowDistrolessDockerPlugin)
  .settings(sourceDirectory := (kafka / sourceDirectory).value)
  .settings(kafkaDistrolessBuildSettings)
  .settings(libraryDependencies ++= kafkaDependencies)
  .settings(excludeDependencies ++= exclusions)
  .settings(addCompilerPlugin(betterMonadicFor))
  .dependsOn(commonFs2)

lazy val rabbitmq = project
  .in(file("modules/rabbitmq"))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, SnowplowDockerPlugin)
  .settings(rabbitmqBuildSettings)
  .settings(libraryDependencies ++= rabbitmqDependencies)
  .settings(excludeDependencies ++= exclusions)
  .settings(addCompilerPlugin(betterMonadicFor))
  .dependsOn(commonFs2)

lazy val rabbitmqDistroless = project
  .in(file("modules/distroless/rabbitmq"))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, SnowplowDistrolessDockerPlugin)
  .settings(sourceDirectory := (rabbitmq / sourceDirectory).value)
  .settings(rabbitmqDistrolessBuildSettings)
  .settings(libraryDependencies ++= rabbitmqDependencies)
  .settings(excludeDependencies ++= exclusions)
  .settings(addCompilerPlugin(betterMonadicFor))
  .dependsOn(commonFs2)

lazy val nsq = project
  .in(file("modules/nsq"))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, SnowplowDockerPlugin)
  .settings(nsqBuildSettings)
  .settings(libraryDependencies ++= nsqDependencies)
  .settings(excludeDependencies ++= exclusions)
  .settings(addCompilerPlugin(betterMonadicFor))
  .dependsOn(commonFs2)

lazy val nsqDistroless = project
  .in(file("modules/distroless/nsq"))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, SnowplowDistrolessDockerPlugin)
  .settings(sourceDirectory := (nsq / sourceDirectory).value)
  .settings(nsqDistrolessBuildSettings)
  .settings(libraryDependencies ++= nsqDependencies ++ Seq(
    // integration tests dependencies
    specs2CEIt,
    testContainersIt
  ))
  .settings(excludeDependencies ++= exclusions)
  .settings(addCompilerPlugin(betterMonadicFor))
  .dependsOn(commonFs2 % "compile->compile;it->it")
  .settings(Defaults.itSettings)
  .configs(IntegrationTest)
  .settings((IntegrationTest / test) := (IntegrationTest / test).dependsOn(Docker / publishLocal).value)
  .settings((IntegrationTest / testOnly) := (IntegrationTest / testOnly).dependsOn(Docker / publishLocal).evaluated)

lazy val eventbridge = project
  .in(file("modules/eventbridge"))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, SnowplowDockerPlugin)
  .settings(eventbridgeBuildSettings)
  .settings(libraryDependencies ++= eventbridgeDependencies)
  .settings(excludeDependencies ++= exclusions)
  .settings(addCompilerPlugin(betterMonadicFor))
  .dependsOn(commonFs2)
  .dependsOn(kinesis)

lazy val eventbridgeDistroless = project
  .in(file("modules/distroless/eventbridge"))
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, SnowplowDistrolessDockerPlugin)
  .settings(sourceDirectory := (eventbridge / sourceDirectory).value)
  .settings(eventbridgeDistrolessBuildSettings)
  .settings(libraryDependencies ++= eventbridgeDependencies ++ Seq(
    // integration tests dependencies
    specs2CEIt,
    testContainersIt
  ))
  .settings(excludeDependencies ++= exclusions)
  .settings(addCompilerPlugin(betterMonadicFor))
  .dependsOn(commonFs2 % "compile->compile;it->it")
  .dependsOn(kinesis % "compile->compile;it->it")
  .settings(Defaults.itSettings)
  .configs(IntegrationTest)
  .settings((IntegrationTest / test) := (IntegrationTest / test).dependsOn(Docker / publishLocal).value)
  .settings((IntegrationTest / testOnly) := (IntegrationTest / testOnly).dependsOn(Docker / publishLocal).evaluated)

