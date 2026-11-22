ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "io.konsume"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all")

val circeVersion = "0.14.15"

lazy val root = (project in file("."))
  .aggregate(core, bench, buildYourOwn)
  .settings(publish / skip := true)

lazy val core = (project in file("core"))
  .settings(
    name := "konsume-core",
    libraryDependencies ++= Seq(
      "io.circe"       %% "circe-core"     % circeVersion,
      "io.circe"       %% "circe-parser"   % circeVersion,
      "org.apache.avro" % "avro"           % "1.12.1",
      "org.scalameta"  %% "munit"          % "1.2.4" % Test
    )
  )

lazy val bench = (project in file("bench"))
  .dependsOn(core)
  .enablePlugins(JmhPlugin)
  .settings(publish / skip := true)

/** A small konsume, built chapter by chapter to understand the real one: see
  * build-your-own/README.md. Depends on nothing.
  */
lazy val buildYourOwn = (project in file("build-your-own"))
  .settings(
    publish / skip := true,
    libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test
  )
