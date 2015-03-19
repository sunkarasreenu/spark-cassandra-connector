/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

import sbt._
import sbt.Keys._

object CassandraSparkBuild extends Build {
  import Versions.scalaBinary
  import Settings._

  val namespace = "spark-cassandra-connector"

  val demosPath = file(s"$namespace-demos")

  lazy val root = RootProject("root", file("."), Seq(embedded, connector, jconnector, demos))

  lazy val embedded = CrossScalaVersionsProject(
    name = s"$namespace-embedded",
    conf = defaultSettings ++ Seq(libraryDependencies ++= Dependencies.embedded)
  ) configs (IntegrationTest, ClusterIntegrationTest)

  lazy val connector = CrossScalaVersionsProject(
    name = namespace,
    conf = assembledSettings ++ Seq(libraryDependencies ++= Dependencies.connector ++ Seq(
        "org.scala-lang" % "scala-reflect"  % scalaVersion.value,
        "org.scala-lang" % "scala-compiler" % scalaVersion.value % "test,it"))
    ).copy(dependencies = Seq(embedded % "test->test;it->it,test;")
  ) configs (IntegrationTest, ClusterIntegrationTest)

  lazy val jconnector = Project(
    id = s"$namespace-java",
    base = file(s"$namespace-java"),
    settings = connector.settings,
    dependencies = Seq(connector % "compile;runtime->runtime;test->test;it->it,test;provided->provided")
  ) configs (IntegrationTest, ClusterIntegrationTest)

  lazy val demos = RootProject("demos", demosPath, Seq(simpleDemos, kafkaStreaming, twitterStreaming))

  lazy val simpleDemos = Project(
    id = "simple-demos",
    base = demosPath / "simple-demos",
    settings = demoSettings,
    dependencies = Seq(connector, jconnector, embedded)
  )

  lazy val kafkaStreaming = CrossScalaVersionsProject(
    name = "kafka-streaming",
    conf = demoSettings ++ Seq(
      excludeFilter in unmanagedSources := (CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, minor)) if minor < 11 => HiddenFileFilter || "*Scala211App*"
        case _ => HiddenFileFilter || "*WordCountApp*"
      }),
      libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, minor)) if minor < 11 => Dependencies.kafka
        case _ => Seq.empty
      }))
  ).copy(base = demosPath / "kafka-streaming", dependencies = Seq(connector, embedded))

  lazy val twitterStreaming = Project(
    id = "twitter-streaming",
    base = demosPath / "twitter-streaming",
    settings = demoSettings ++ Seq(libraryDependencies ++= Dependencies.twitter),
    dependencies = Seq(connector)
  )

  def crossBuildPath(base: sbt.File, v: String): sbt.File = base / s"scala-$v" / "src"

  /* templates */
  def CrossScalaVersionsProject(name: String,
                                conf: Seq[Def.Setting[_]],
                                reliesOn: Seq[ClasspathDep[ProjectReference]] = Seq.empty) =
    Project(id = name, base = file(name), dependencies = reliesOn, settings = conf ++ Seq(
      unmanagedSourceDirectories in (Compile, packageBin) +=
        crossBuildPath(baseDirectory.value, scalaBinaryVersion.value),
      unmanagedSourceDirectories in (Compile, doc) +=
        crossBuildPath(baseDirectory.value, scalaBinaryVersion.value),
      unmanagedSourceDirectories in Compile +=
        crossBuildPath(baseDirectory.value, scalaBinaryVersion.value)
    ))

  def RootProject(name: String, dir: sbt.File, contains: Seq[ProjectReference]): Project =
    Project(id = name, base = dir, settings = parentSettings, aggregate = contains)

}

object Dependencies {
  import Versions._

  implicit class Exclude(module: ModuleID) {
     def guavaExclude: ModuleID =
       module exclude("com.google.guava", "guava")

     def sparkExclusions: ModuleID = module.guavaExclude
       .exclude("org.apache.spark", s"spark-core_$scalaBinary")

     def logbackExclude: ModuleID = module
       .exclude("ch.qos.logback", "logback-classic")
       .exclude("ch.qos.logback", "logback-core")

     def replExclusions: ModuleID = module.guavaExclude
       .exclude("org.apache.spark", s"spark-bagel_$scalaBinary")
       .exclude("org.apache.spark", s"spark-mllib_$scalaBinary")
       .exclude("org.scala-lang", "scala-compiler")

     def kafkaExclusions: ModuleID = module
       .exclude("org.slf4j", "slf4j-simple")
       .exclude("com.sun.jmx", "jmxri")
       .exclude("com.sun.jdmk", "jmxtools")
       .exclude("net.sf.jopt-simple", "jopt-simple")
  }

  object Compile {

    val akkaActor           = "com.typesafe.akka"       %% "akka-actor"            % Akka           % "provided"  // ApacheV2
    val akkaRemote          = "com.typesafe.akka"       %% "akka-remote"           % Akka           % "provided"  // ApacheV2
    val akkaSlf4j           = "com.typesafe.akka"       %% "akka-slf4j"            % Akka           % "provided"  // ApacheV2
    val cassandraThrift     = "org.apache.cassandra"    % "cassandra-thrift"       % Cassandra       guavaExclude // ApacheV2
    val cassandraClient     = "org.apache.cassandra"    % "cassandra-clientutil"   % Cassandra       guavaExclude // ApacheV2
    val cassandraDriver     = "com.datastax.cassandra"  % "cassandra-driver-core"  % CassandraDriver guavaExclude // ApacheV2
    val commonsLang3        = "org.apache.commons"      % "commons-lang3"          % CommonsLang3                 // ApacheV2
    val config              = "com.typesafe"            % "config"                 % Config         % "provided"  // ApacheV2
    val guava               = "com.google.guava"        % "guava"                  % Guava
    val jodaC               = "org.joda"                % "joda-convert"           % JodaC
    val jodaT               = "joda-time"               % "joda-time"              % JodaT
    val lzf                 = "com.ning"                % "compress-lzf"           % Lzf            % "provided"
    val slf4jApi            = "org.slf4j"               % "slf4j-api"              % Slf4j          % "provided"  // MIT
    /* To allow spark artifact inclusion in the demos at runtime, we set 'provided' below. */
    val sparkCore           = "org.apache.spark"        %% "spark-core"            % Spark guavaExclude           // ApacheV2
    val sparkStreaming      = "org.apache.spark"        %% "spark-streaming"       % Spark guavaExclude           // ApacheV2
    val sparkSql            = "org.apache.spark"        %% "spark-sql"             % Spark sparkExclusions        // ApacheV2
    val sparkCatalyst       = "org.apache.spark"        %% "spark-catalyst"        % Spark sparkExclusions        // ApacheV2
    val sparkHive           = "org.apache.spark"        %% "spark-hive"            % Spark sparkExclusions        // ApacheV2

    object Metrics {
      val metricsCore       = "com.codahale.metrics"    % "metrics-core"            % CodaHaleMetrics
      val metricsJson       = "com.codahale.metrics"    % "metrics-json"            % CodaHaleMetrics % "provided"
    }

    object Embedded {
      val akkaCluster       = "com.typesafe.akka"       %% "akka-cluster"           % Akka                        // ApacheV2
      val cassandraServer   = "org.apache.cassandra"    % "cassandra-all"           % Cassandra logbackExclude    // ApacheV2
      val jopt              = "net.sf.jopt-simple"      % "jopt-simple"             % JOpt
      val kafka             = "org.apache.kafka"        %% "kafka"                  % Kafka     kafkaExclusions   // ApacheV2
      val sparkRepl         = "org.apache.spark"        %% "spark-repl"             % Spark     replExclusions    // ApacheV2
    }

    object Demos {
      val kafka             = "org.apache.kafka"        % "kafka_2.10"               % Kafka    kafkaExclusions   // ApacheV2
      val kafkaStreaming    = "org.apache.spark"        % "spark-streaming-kafka_2.10" % Spark  sparkExclusions   // ApacheV2
      val twitterStreaming  = "org.apache.spark"        %% "spark-streaming-twitter" % Spark    sparkExclusions   // ApacheV2
    }

    object Test {
      val akkaTestKit       = "com.typesafe.akka"       %% "akka-testkit"           % Akka      % "test,it"       // ApacheV2
      val commonsIO         = "commons-io"              % "commons-io"              % CommonsIO % "test,it"       // ApacheV2
      val scalaMock         = "org.scalamock"           %% "scalamock-scalatest-support" % ScalaMock % "test,it"  // BSD
      val scalaTest         = "org.scalatest"           %% "scalatest"              % ScalaTest % "test,it"       // ApacheV2
      val scalactic         = "org.scalactic"           %% "scalactic"              % Scalactic % "test,it"       // ApacheV2
      val mockito           = "org.mockito"             % "mockito-all"             % "1.10.19" % "test,it"       // MIT
      val junit             = "junit"                   % "junit"                   % "4.11"    % "test,it"
      val junitInterface    = "com.novocode"            % "junit-interface"         % "0.10"    % "test,it"
    }
  }

  import Compile._


  val logging = Seq(slf4jApi)

  val metrics = Seq(Metrics.metricsCore, Metrics.metricsJson)

  val testKit = Seq(Test.akkaTestKit, Test.commonsIO, Test.junit,
   Test.junitInterface, Test.scalaMock, Test.scalaTest, Test.scalactic, Test.mockito)

  val akka = Seq(akkaActor, akkaRemote, akkaSlf4j)

  val cassandra = Seq(cassandraThrift, cassandraClient, cassandraDriver)

  val spark = Seq(sparkCore, sparkStreaming, sparkSql, sparkCatalyst, sparkHive)

  val connector = testKit ++ metrics ++ logging ++ akka ++ cassandra ++ spark.map(_ % "provided") ++ Seq(
    commonsLang3, config, guava, jodaC, jodaT, lzf)

  val embedded = logging ++ spark ++ cassandra ++ Seq(
    Embedded.cassandraServer, Embedded.jopt, Embedded.sparkRepl, Embedded.kafka)

  val kafka = Seq(Demos.kafka, Demos.kafkaStreaming)

  val twitter = Seq(sparkStreaming, Demos.twitterStreaming)
}
