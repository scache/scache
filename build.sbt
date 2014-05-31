name := "scala-loading-cache"

version := "0.0.1-SNAPSHOT"

organization := "arfaian"

scalaVersion := "2.10.4"

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

net.virtualvoid.sbt.graph.Plugin.graphSettings

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

publishTo := Some("Revmetrix Snapshot Repository" at "http://nexus.revmetrix.com:8081/nexus/content/repositories/snapshots")

libraryDependencies ++= Seq(
  "com.typesafe.scala-logging" %% "scala-logging-slf4j" % "2.1.2",
  "ch.qos.logback" % "logback-classic" % "1.1.2",
  "org.monifu" %% "monifu-core" % "0.8.0",
  "com.assembla.scala-incubator" %% "graph-core" % "1.8.0",
  "com.assembla.scala-incubator" %% "graph-constrained" % "1.8.0",
  "org.scalatest" %% "scalatest" % "2.1.4" % "test",
  "org.scalamock" %% "scalamock-scalatest-support" % "3.1.RC1" % "test"
)
