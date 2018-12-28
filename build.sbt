
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


lazy val registry = (project in file(".")).
  enablePlugins(JavaAppPackaging).
  enablePlugins(DockerPlugin).
  enablePlugins(ScalastylePlugin).
  settings(
    dockerBaseImage := "openjdk:jre-alpine"
  ).
  enablePlugins(AshScriptPlugin)

libraryDependencies ++= List(
  "com.typesafe.slick" %% "slick" % "3.2.3",
  "com.typesafe.slick" %% "slick-hikaricp" % "3.2.3",
  "com.typesafe.slick" %% "slick-codegen" % "3.2.3",
  "mysql" % "mysql-connector-java" % "5.1.34",
  "org.slf4j" % "slf4j-nop" % "1.6.4"
)

libraryDependencies += "com.h2database" % "h2" % "1.4.197"

trapExit := false