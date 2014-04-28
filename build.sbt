name := "scala-loading-cache"

organization := "arfaian"

version := "0.0.1-SNAPSHOT"

scalaVersion := "2.10.4"

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

net.virtualvoid.sbt.graph.Plugin.graphSettings

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

publishTo := Some("Revmetrix Snapshot Repository" at "http://nexus.revmetrix.com:8081/nexus/content/repositories/snapshots")

libraryDependencies ++= Seq(
  "com.typesafe.scala-logging" %% "scala-logging-slf4j" % "2.1.2",
  "ch.qos.logback" % "logback-classic" % "1.1.2",
  "org.monifu" %% "monifu-core" % "0.7.1",
  "org.scalatest" %% "scalatest" % "2.1.4" % "test"
)
