import net.virtualvoid.sbt.graph.Plugin._
import sbt._
import sbt.Keys._
import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm
import com.github.retronym.SbtOneJar



parallelExecution in Test := false

val baseSettings: Seq[Def.Setting[_]] =
  graphSettings ++
  Seq(
    name := "akka-dddd-template",
    version := "1.1.0",
    organization := "boldradius",
    scalaVersion := "2.11.6",
    ivyScala := ivyScala.value map { _.copy(overrideScalaVersion = true) },
    org.scalastyle.sbt.PluginKeys.config := file("project/scalastyle-config.xml"),
    scalacOptions in Compile ++= Seq("-encoding", "UTF-8", "-target:jvm-1.8", "-deprecation", "-unchecked", "-Ywarn-dead-code", "-Xfatal-warnings", "-feature", "-language:postfixOps"),
    scalacOptions in (Compile, doc) <++= (name in (Compile, doc), version in (Compile, doc)) map DefaultOptions.scaladoc,
    javacOptions in (Compile, compile) ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint:unchecked", "-Xlint:deprecation", "-Xlint:-options"),
    javacOptions in doc := Seq(),
    javaOptions += "-Xmx2G",
    outputStrategy := Some(StdoutOutput),
    exportJars := true,
    fork := true,
    resolvers := ResolverSettings.resolvers,
    Keys.fork in run := true,
    // make sure that MultiJvm test are compiled by the default test compilation
    compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test),
    // disable parallel tests
    parallelExecution in Test := false,
    // make sure that MultiJvm tests are executed by the default test target,
    // and combine the results from ordinary test and multi-jvm tests
    artifact in oneJar <<= moduleName(Artifact(_)),
    executeTests in Test <<= (executeTests in Test, executeTests in MultiJvm) map {
      case (testResults, multiNodeResults)  =>
        val overall =
          if (testResults.overall.id < multiNodeResults.overall.id)
            multiNodeResults.overall
          else
            testResults.overall
        Tests.Output(overall,
          testResults.events ++ multiNodeResults.events,
          testResults.summaries ++ multiNodeResults.summaries)
    }
  )


val akka = "2.4.11"
val Spray = "1.3.1"

lazy val root =  project.in( file(".") )
  .settings( baseSettings ++ SbtMultiJvm.multiJvmSettings  ++ SbtOneJar.oneJarSettings ++ Defaults.itSettings :_*)
  .settings( libraryDependencies ++= {
        Seq(
          "io.spray"                   %% "spray-routing"   % Spray    % "compile",
          "io.spray"                   %% "spray-can"       % Spray    % "compile",
          "io.spray"                   %% "spray-json"      % Spray    % "compile",
          "io.spray"                   %% "spray-testkit"   % Spray    % "test",
          "org.json4s"                 %% "json4s-native"   % "3.2.11",
          "com.typesafe.akka"          %% "akka-actor"                             % akka,
          "com.typesafe.akka"          %% "akka-cluster"                           % akka,
          "com.typesafe.akka"          %% "akka-cluster-sharding"                  % akka,
          "com.typesafe.akka"          %% "akka-persistence"                       % akka,
          "com.typesafe.akka"          %% "akka-persistence-query-experimental"    % akka,
          "com.typesafe.akka"          %% "akka-remote"                            % akka,
          "com.typesafe.akka"          %% "akka-contrib"                           % akka,
          "com.typesafe.akka"          %% "akka-slf4j"                             % akka,
          "com.typesafe.akka"          %% "akka-multi-node-testkit"                % akka,
          "com.typesafe.akka"          %% "akka-testkit"                           % akka     % "test",
          "com.typesafe.akka"          %% "akka-persistence-cassandra"             % "0.18",
          "org.slf4j"                  %  "slf4j-api"                              % "1.7.7",
          "com.typesafe.scala-logging" %% "scala-logging"                          % "3.0.0",
          "ch.qos.logback"             %  "logback-core"                           % "1.1.2",
          "ch.qos.logback"             %  "logback-classic"                        % "1.1.2",
          "org.scala-lang.modules"     %  "scala-xml_2.11"                         % "1.0.3",
          "org.scala-lang.modules"     %  "scala-xml_2.11"                         % "1.0.3",
          "org.scalatest"              %% "scalatest"                              % "2.2.1"  % "test",
          "org.iq80.leveldb"           %  "leveldb"                                % "0.7",
          "org.fusesource.leveldbjni"  %  "leveldbjni-all"                         % "1.8",
          "org.json4s"                 %% "json4s-native"                          % "3.2.11",
          "joda-time" 				         %  "joda-time" 						                 % "2.7",
          "org.joda"                   %  "joda-convert"                           % "1.2",
          "com.datastax.cassandra"     %  "cassandra-driver-core" 				         % "3.1.0"  exclude("org.xerial.snappy", "snappy-java"),
          "commons-io"                 %  "commons-io"                             % "2.4"    % "test",
          "org.xerial.snappy"          %  "snappy-java"           				         % "1.1.1.3"
        )
      }
  ).configs (MultiJvm)





