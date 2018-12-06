
name := "delphi-registry"

version := "1.0.0-SNAPSHOT"

scalaVersion := "2.12.4"


val akkaVersion = "2.5.14"
libraryDependencies += "com.typesafe.akka" %% "akka-http" % "10.0.11"
libraryDependencies += "com.typesafe.akka" %% "akka-stream" % akkaVersion
libraryDependencies += "com.typesafe.akka" %% "akka-http-spray-json" % "10.1.1"

libraryDependencies += "io.spray" %%  "spray-json" % "1.3.3"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.4" % "test"


libraryDependencies += "org.parboiled" %% "parboiled" % "2.1.4"
libraryDependencies += "com.typesafe.akka" %% "akka-http-testkit" % "10.1.5"

libraryDependencies += "com.pauldijou" %% "jwt-core" % "1.0.0"

lazy val registry = (project in file(".")).
  enablePlugins(JavaAppPackaging).
  enablePlugins(DockerPlugin).
  enablePlugins(ScalastylePlugin).
  settings (
    dockerBaseImage := "openjdk:jre-alpine"
  )

