/*
 * Copyright (c) 2012-2023 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the
 * Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.  See the Apache License Version 2.0 for the specific
 * language governing permissions and limitations there under.
 */

import sbt._
import sbt.Keys._
import sbt.nio.Keys.{ReloadOnSourceChanges, onChangedBuildSource}
import sbtassembly.AssemblyPlugin.autoImport._
import sbtbuildinfo.BuildInfoPlugin.autoImport.{BuildInfoKey, buildInfoKeys, buildInfoPackage}
import sbtdynver.DynVerPlugin.autoImport._
import com.typesafe.sbt.SbtNativePackager.autoImport._
import com.typesafe.sbt.packager.archetypes.jar.LauncherJarPlugin.autoImport.packageJavaLauncherJar
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport._
import com.typesafe.sbt.packager.linux.LinuxPlugin.autoImport._
import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport._
import org.scalafmt.sbt.ScalafmtPlugin.autoImport._
import scoverage.ScoverageKeys._

object BuildSettings {

  // PROJECT

  lazy val projectSettings = Seq(
    organization := "com.snowplowanalytics",
    scalaVersion := "2.12.18",
    licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html"))
  )

  lazy val commonProjectSettings = projectSettings ++ Seq(
    name := "opensnowcat-common-enrich",
    moduleName := "opensnowcat-common-enrich",
    description := "Common functionality for enriching raw Snowplow events"
  )

  lazy val commonFs2ProjectSettings = projectSettings ++ Seq(
    name := "opensnowcat-enrich-common-fs2",
    moduleName := "opensnowcat-enrich-common-fs2",
    description := "Common functionality for streaming enrich applications built on top of functional streams"
  )

  lazy val awsUtilsProjectSettings = projectSettings ++ Seq(
    name := "opensnowcat-enrich-aws-utils",
    moduleName := "opensnowcat-enrich-aws-utils",
    description := "AWS specific utils"
  )

  lazy val gcpUtilsProjectSettings = projectSettings ++ Seq(
    name := "opensnowcat-enrich-gcp-utils",
    moduleName := "opensnowcat-enrich-gcp-utils",
    description := "GCP specific utils"
  )

  lazy val azureUtilsProjectSettings = projectSettings ++ Seq(
    name := "opensnowcat-enrich-azure-utils",
    moduleName := "opensnowcat-enrich-azure-utils",
    description := "Azure specific utils"
  )

  lazy val pubsubProjectSettings = projectSettings ++ Seq(
    name := "opensnowcat-enrich-pubsub",
    moduleName := "opensnowcat-enrich-pubsub",
    description := "High-performance streaming enrich app working with Pub/Sub, built on top of functional streams",
    buildInfoKeys := Seq[BuildInfoKey](organization, name, version, description),
    buildInfoPackage := "com.snowplowanalytics.snowplow.enrich.pubsub.generated"
  )

  lazy val kinesisProjectSettings = projectSettings ++ Seq(
    name := "opensnowcat-enrich-kinesis",
    moduleName := "opensnowcat-enrich-kinesis",
    description := "High-performance streaming enrich app working with Kinesis, built on top of functional streams",
    buildInfoKeys := Seq[BuildInfoKey](organization, name, version, description),
    buildInfoPackage := "com.snowplowanalytics.snowplow.enrich.kinesis.generated"
  )

  lazy val kafkaProjectSettings = projectSettings ++ Seq(
    name := "opensnowcat-enrich-kafka",
    moduleName := "opensnowcat-enrich-kafka",
    description := "High-performance streaming enrich app working with Kafka, built on top of functional streams",
    buildInfoKeys := Seq[BuildInfoKey](organization, name, version, description),
    buildInfoPackage := "com.snowplowanalytics.snowplow.enrich.kafka.generated"
  )

  lazy val nsqProjectSettings = projectSettings ++ Seq(
    name := "opensnowcat-enrich-nsq",
    moduleName := "opensnowcat-enrich-nsq",
    description := "High-performance streaming enrich app working with Nsq, built on top of functional streams",
    buildInfoKeys := Seq[BuildInfoKey](organization, name, version, description),
    buildInfoPackage := "com.snowplowanalytics.snowplow.enrich.nsq.generated"
  )

  lazy val eventbridgeProjectSettings = projectSettings ++ Seq(
    name := "opensnowcat-enrich-eventbridge",
    moduleName := "opensnowcat-enrich-eventbridge",
    description := "High-performance streaming enrich app working with EventBridge, built on top of functional streams",
    buildInfoKeys := Seq[BuildInfoKey](organization, name, version, description),
    buildInfoPackage := "com.snowplowanalytics.snowplow.enrich.eventbridge.generated"
  )

  /** Make package (build) metadata available within source code. */
  lazy val scalifiedSettings = Seq(
    Compile / sourceGenerators += Def.task {
      val file = (Compile / sourceManaged).value / "settings.scala"
      IO.write(
        file,
        """package com.snowplowanalytics.snowplow.enrich.common.generated
                       |object ProjectSettings {
                       |  val version = "%s"
                       |  val name = "%s"
                       |  val organization = "%s"
                       |  val scalaVersion = "%s"
                       |}
                       |""".stripMargin.format(version.value, name.value, organization.value, scalaVersion.value)
      )
      Seq(file)
    }.taskValue
  )

  lazy val compilerSettings = Seq(
    javacOptions := Seq("-source", "11", "-target", "11")
  )

  lazy val resolverSettings = Seq(
    resolvers ++= Dependencies.resolutionRepos
  )

  lazy val formattingSettings = Seq(
    scalafmtConfig := file(".scalafmt.conf"),
    scalafmtOnCompile := false
  )

  // BUILD AND PUBLISH

  /** Snowplow Common Enrich Maven publishing settings. */
  lazy val publishSettings = Seq(
    publishArtifact := true,
    Test / publishArtifact := false,
    pomIncludeRepository := { _ => false },
    homepage := Some(url("http://snowplowanalytics.com")),
    ThisBuild / dynverVTagPrefix := false, // Otherwise git tags are required to have v-prefix
    ThisBuild / dynverSeparator := "-", // to be compatible with Docker
    developers := List(
      Developer(
        "Snowplow Analytics Ltd",
        "Snowplow Analytics Ltd",
        "support@snowplowanalytics.com",
        url("https://snowplowanalytics.com")
      )
    )
  )

  lazy val assemblySettings = Seq(
    assembly / assemblyJarName := { s"${moduleName.value}-${version.value}.jar" },
    assembly / assemblyMergeStrategy := {
      case x if x.endsWith(".properties") => MergeStrategy.first
      case x if x.endsWith("public-suffix-list.txt") => MergeStrategy.first
      case x if x.endsWith("ProjectSettings$.class") => MergeStrategy.first
      case x if x.endsWith("module-info.class") => MergeStrategy.first
      case x if x.endsWith("nowarn.class") => MergeStrategy.first
      case x if x.endsWith("nowarn$.class") => MergeStrategy.first
      case x if x.endsWith("log4j.properties") => MergeStrategy.first
      case x if x.endsWith(".proto") => MergeStrategy.first
      case x if x.endsWith("reflection-config.json") => MergeStrategy.first
      case x if x.endsWith("config.fmpp") => MergeStrategy.first
      case x if x.contains("simulacrum") => MergeStrategy.first
      case x if x.endsWith("git.properties") => MergeStrategy.discard
      case x if x.endsWith(".json") => MergeStrategy.first
      case x if x.endsWith("AUTHORS") => MergeStrategy.first
      case x if x.endsWith(".config") => MergeStrategy.first
      case x if x.endsWith(".types") => MergeStrategy.first
      case x if x.contains("netty") => MergeStrategy.first
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )

  lazy val dockerSettingsFocal = Seq(
    Universal / javaOptions ++= Seq("-Dnashorn.args=--language=es6")
  )

  lazy val dockerSettingsDistroless = Seq(
    dockerEntrypoint := {
      val orig = dockerEntrypoint.value
      orig.head +: "-Dnashorn.args=--language=es6" +: orig.tail
    }
  )

  // TESTS

  lazy val scoverageSettings = Seq(
    coverageMinimumStmtTotal := 50,
    coverageFailOnMinimum := false,
    (Test / test) := {
      (coverageReport dependsOn (Test / test)).value
    }
  )

  lazy val noParallelTestExecution = Seq(Test / parallelExecution := false)

  /** Add example config for integration tests. */
  lazy val addExampleConfToTestCp = Seq(
    Test / unmanagedClasspath += {
      baseDirectory.value.getParentFile.getParentFile / "config"
    }
  )

  lazy val buildSettings = Seq(
    Global / cancelable := true,
    Global / onChangedBuildSource := ReloadOnSourceChanges
  ) ++ compilerSettings ++ resolverSettings ++ formattingSettings

  lazy val commonBuildSettings = {
    // Project
    commonProjectSettings ++ buildSettings ++ scalifiedSettings ++
      // Build and publish
      publishSettings ++
      // Tests
      scoverageSettings ++ noParallelTestExecution
  }

  lazy val commonFs2BuildSettings = {
    // Project
    commonFs2ProjectSettings ++ buildSettings ++
      // Tests
      scoverageSettings ++ noParallelTestExecution ++ addExampleConfToTestCp
  }

  lazy val awsUtilsBuildSettings = {
    // Project
    awsUtilsProjectSettings ++ buildSettings ++
      // Tests
      scoverageSettings ++ noParallelTestExecution ++ addExampleConfToTestCp
  }

  lazy val gcpUtilsBuildSettings = {
    // Project
    gcpUtilsProjectSettings ++ buildSettings ++
      // Tests
      scoverageSettings ++ noParallelTestExecution ++ addExampleConfToTestCp
  }

  lazy val azureUtilsBuildSettings = {
    // Project
    azureUtilsProjectSettings ++ buildSettings ++
      // Tests
      scoverageSettings ++ noParallelTestExecution ++ addExampleConfToTestCp
  }

  lazy val pubsubBuildSettings = {
    // Project
    pubsubProjectSettings ++ buildSettings ++
      // Build and publish
      assemblySettings ++ dockerSettingsFocal ++
      Seq(Docker / packageName := "opensnowcat-enrich-pubsub") ++
      // Tests
      scoverageSettings ++ noParallelTestExecution ++ addExampleConfToTestCp
  }

  lazy val pubsubDistrolessBuildSettings = pubsubBuildSettings.diff(dockerSettingsFocal) ++ dockerSettingsDistroless

  lazy val kinesisBuildSettings = {
    // Project
    kinesisProjectSettings ++ buildSettings ++
      // Build and publish
      assemblySettings ++ dockerSettingsFocal ++
      Seq(Docker / packageName := "opensnowcat-enrich-kinesis") ++
      // Tests
      scoverageSettings ++ noParallelTestExecution ++ Seq(Test / fork := true) ++ addExampleConfToTestCp
  }

  lazy val kinesisDistrolessBuildSettings = kinesisBuildSettings.diff(dockerSettingsFocal) ++ dockerSettingsDistroless

  lazy val kafkaBuildSettings = {
    // Project
    kafkaProjectSettings ++ buildSettings ++
      // Build and publish
      assemblySettings ++ dockerSettingsFocal ++
      Seq(Docker / packageName := "opensnowcat-enrich-kafka") ++
      // Tests
      scoverageSettings ++ noParallelTestExecution ++ addExampleConfToTestCp
  }

  lazy val kafkaDistrolessBuildSettings = kafkaBuildSettings.diff(dockerSettingsFocal) ++ dockerSettingsDistroless

  lazy val nsqBuildSettings = {
    // Project
    nsqProjectSettings ++ buildSettings ++
      // Build and publish
      assemblySettings ++ dockerSettingsFocal ++
      Seq(Docker / packageName := "opensnowcat-enrich-nsq") ++
      // Tests
      scoverageSettings ++ noParallelTestExecution ++ addExampleConfToTestCp
  }

  lazy val nsqDistrolessBuildSettings = nsqBuildSettings.diff(dockerSettingsFocal) ++ dockerSettingsDistroless

  lazy val eventbridgeBuildSettings = {
    // Project
    eventbridgeProjectSettings ++ buildSettings ++
      // Build and publish
      assemblySettings ++ dockerSettingsFocal ++
      Seq(Docker / packageName := "opensnowcat-enrich-eventbridge") ++
      // Tests
      scoverageSettings ++ noParallelTestExecution ++ Seq(Test / fork := true) ++ addExampleConfToTestCp
  }

  lazy val eventbridgeDistrolessBuildSettings = eventbridgeBuildSettings.diff(dockerSettingsFocal) ++ dockerSettingsDistroless

  /** Fork a JVM per test in order to not reuse enrichment registries. */
  def oneJVMPerTest(tests: Seq[TestDefinition]): Seq[Tests.Group] =
    tests.map(t => Tests.Group(t.name, Seq(t), Tests.SubProcess(ForkOptions())))
}
