import Dependencies._

ThisBuild / scalaVersion     := "2.13.5"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "com.example"
ThisBuild / organizationName := "example"

lazy val catsVersion = "2.6.0"
lazy val catsEffectVersion = "3.1.0"
lazy val fs2Version = "3.0.2"

lazy val root = (project in file("."))
  .settings(
    name := "fs2bug",
    libraryDependencies ++= Seq(
        "org.typelevel" %% "cats-core" % catsVersion,
        "org.typelevel" %% "cats-free" % catsVersion,
        "org.typelevel" %% "cats-effect" % catsEffectVersion,
        "org.typelevel" %% "alleycats-core" % catsVersion,
        "co.fs2" %% "fs2-core" % fs2Version,
      )
  )

  fork in run := true

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.
