ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "io.konsume"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all")

val circeVersion = "0.14.15"

lazy val root = (project in file("."))
  .aggregate(core, bench)
  .settings(publish / skip := true)

lazy val core = (project in file("core"))
  .settings(
    name := "konsume-core",
    libraryDependencies ++= Seq(
      // The staging compiler must be exactly the same version as the Scala compiler.
      "org.scala-lang" %% "scala3-staging" % scalaVersion.value,
      "io.circe"       %% "circe-core"     % circeVersion,
      "io.circe"       %% "circe-parser"   % circeVersion,
      "org.apache.avro" % "avro"           % "1.12.1",
      "org.scalameta"  %% "munit"          % "1.2.4" % Test
    ),
    // The staging compiler builds its classpath from java.class.path: it does not
    // see sbt's layered class loaders, so anything that runs staged code must fork.
    Test / fork := true,
    run / fork  := true
  )

lazy val bench = (project in file("bench"))
  .dependsOn(core)
  .enablePlugins(JmhPlugin)
  .settings(publish / skip := true)
