/*
 * Copyright (c) 2012-2021 Snowplow Analytics Ltd. All rights reserved.
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

lazy val root = project.in(file(".")).aggregate(common, aws, loader, redshiftLoader, shredder, streamShredder)

lazy val aws = project
  .in(file("modules/aws"))
  .settings(BuildSettings.buildSettings)
  .settings(
    addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
    libraryDependencies ++= Seq(
      Dependencies.aws2s3,
      Dependencies.aws2sqs,
      Dependencies.aws2sns,
      Dependencies.fs2,
      Dependencies.catsRetry
    )
  )
  .enablePlugins(BuildInfoPlugin)

lazy val common: Project = project
  .in(file("modules/common"))
  .settings(
    Seq(
      name := "snowplow-rdb-loader-common",
      buildInfoPackage := "com.snowplowanalytics.snowplow.rdbloader.generated"
    )
  )
  .settings(BuildSettings.scoverageSettings)
  .settings(BuildSettings.buildSettings)
  .settings(BuildSettings.addExampleConfToTestCp)
  .settings(resolvers ++= Dependencies.resolutionRepos)
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.decline,
      Dependencies.badrows,
      Dependencies.igluClient,
      Dependencies.circeGeneric,
      Dependencies.circeGenericExtra,
      Dependencies.circeLiteral,
      Dependencies.pureconfig,
      Dependencies.pureconfigCirce,
      Dependencies.cron4sCirce,
      Dependencies.schemaDdl,
      Dependencies.http4sCore,
      Dependencies.aws2regions,
      Dependencies.specs2,
      Dependencies.monocle,
      Dependencies.monocleMacro
    )
  )
  .enablePlugins(BuildInfoPlugin)

lazy val loader = project
  .in(file("modules/loader"))
  .settings(
    name := "snowplow-rdb-loader",
    initialCommands := "import com.snowplowanalytics.snowplow.rdbloader._",
    Compile / mainClass := Some("com.snowplowanalytics.snowplow.rdbloader.Main")
  )
  .settings(BuildSettings.buildSettings)
  .settings(BuildSettings.addExampleConfToTestCp)
  .settings(BuildSettings.assemblySettings)
  .settings(BuildSettings.dynVerSettings)
  .settings(resolvers ++= Dependencies.resolutionRepos)
  .settings(
    addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
    libraryDependencies ++= Seq(
      Dependencies.slf4j,
      Dependencies.ssm,
      Dependencies.dynamodb,
      Dependencies.jSch,
      Dependencies.sentry,
      Dependencies.scalaTracker,
      Dependencies.scalaTrackerEmit,
      Dependencies.fs2Blobstore,
      Dependencies.fs2Cron,
      Dependencies.http4sCirce,
      Dependencies.http4sClient,
      Dependencies.igluClientHttp4s,
      Dependencies.doobie,
      Dependencies.doobieHikari,
      Dependencies.catsRetry,
      Dependencies.log4cats,
      Dependencies.specs2,
      Dependencies.specs2ScalaCheck,
      Dependencies.catsEffLaws,
      Dependencies.scalaCheck,
      Dependencies.catsTesting
    )
  )
  .dependsOn(common % "compile->compile;test->test", aws)
  .enablePlugins(JavaAppPackaging, DockerPlugin, BuildInfoPlugin)

lazy val redshiftLoader = project
  .in(file("modules/redshift-loader"))
  .settings(
    name := "snowplow-redshift-loader",
    Docker / packageName := "snowplow/rdb-loader-redshift",
    initialCommands := "import com.snowplowanalytics.snowplow.loader.redshift._",
    Compile / mainClass := Some("com.snowplowanalytics.snowplow.loader.redshift.Main")
  )
  .settings(BuildSettings.buildSettings)
  .settings(BuildSettings.addExampleConfToTestCp)
  .settings(BuildSettings.assemblySettings)
  .settings(BuildSettings.dockerSettings)
  .settings(BuildSettings.dynVerSettings)
  .settings(resolvers ++= Dependencies.resolutionRepos)
  .settings(
    addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
    libraryDependencies ++= Seq(
      Dependencies.redshift,
      Dependencies.redshiftSdk
    )
  )
  .dependsOn(common % "compile->compile;test->test", aws, loader % "compile->compile;test->test")
  .enablePlugins(JavaAppPackaging, DockerPlugin, BuildInfoPlugin)

lazy val shredder = project
  .in(file("modules/shredder"))
  .settings(
    name := "snowplow-rdb-shredder",
    description := "Spark job to shred event and context JSONs from Snowplow enriched events",
    buildInfoPackage := "com.snowplowanalytics.snowplow.rdbloader.shredder.batch.generated",
    buildInfoKeys := List(name, version, description),
    BuildSettings.oneJvmPerTestSetting // ensures that only CrossBatchDeduplicationSpec has a DuplicateStorage
  )
  .settings(BuildSettings.buildSettings)
  .settings(resolvers ++= Dependencies.resolutionRepos)
  .settings(BuildSettings.shredderAssemblySettings)
  .settings(BuildSettings.dynVerSettings)
  .settings(
    libraryDependencies ++= Seq(
      // Java
      Dependencies.sqs,
      Dependencies.sns,
      Dependencies.dynamodb,
      Dependencies.slf4j,
      Dependencies.sentry,
      // Scala
      Dependencies.eventsManifest,
      Dependencies.sparkCore,
      Dependencies.sparkSQL,
      Dependencies.jacksonModule,
      Dependencies.jacksonDatabind,
      // Scala (test only)
      Dependencies.circeOptics,
      Dependencies.specs2,
      Dependencies.specs2ScalaCheck,
      Dependencies.scalaCheck
    )
  )
  .dependsOn(common)
  .enablePlugins(BuildInfoPlugin)

lazy val streamShredder = project
  .in(file("modules/stream-shredder"))
  .settings(
    name := "snowplow-rdb-stream-shredder",
    description := "Stream Shredding job",
    buildInfoPackage := "com.snowplowanalytics.snowplow.rdbloader.shredder.stream.generated",
    buildInfoKeys := List(name, version, description),
    Docker / packageName := "snowplow/snowplow-rdb-stream-shredder",
    addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
  )
  .settings(BuildSettings.buildSettings)
  .settings(BuildSettings.assemblySettings)
  .settings(BuildSettings.dockerSettings)
  .settings(BuildSettings.dynVerSettings)
  .settings(resolvers ++= Dependencies.resolutionRepos)
  .settings(
    libraryDependencies ++= Seq(
      // Java
      Dependencies.dynamodb,
      Dependencies.slf4j,
      // Scala
      Dependencies.log4cats,
      Dependencies.fs2Blobstore,
      Dependencies.fs2Io,
      Dependencies.fs2Aws,
      Dependencies.fs2AwsSqs,
      Dependencies.aws2kinesis,
      Dependencies.http4sClient,
      // Scala (test only)
      Dependencies.specs2,
      Dependencies.specs2ScalaCheck,
      Dependencies.scalaCheck
    )
  )
  .dependsOn(common, aws)
  .enablePlugins(JavaAppPackaging, DockerPlugin, BuildInfoPlugin)
