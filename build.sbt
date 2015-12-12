lazy val commonSettings = Seq(
  organization := "fs2",
  scalaVersion := "2.11.7",
  scalacOptions ++= Seq(
    "-feature",
    "-deprecation",
    "-language:implicitConversions",
    "-language:higherKinds",
    "-language:existentials",
    "-language:postfixOps",
    "-Xfatal-warnings",
    "-Yno-adapted-args"
  ),
  libraryDependencies ++= Seq(
    "org.scalacheck" %% "scalacheck" % "1.12.5" % "test"
  ),
  scalacOptions in (Compile, doc) ++= Seq(
    "-doc-source-url", scmInfo.value.get.browseUrl + "/tree/master€{FILE_PATH}.scala",
    "-sourcepath", baseDirectory.in(LocalRootProject).value.getAbsolutePath,
    "-implicits",
    "-implicits-show-all"
  ),
  parallelExecution in Test := false,
  logBuffered in Test := false,
  testOptions in Test += Tests.Argument("-verbosity", "2"),
  autoAPIMappings := true,
  initialCommands := s"""
    import fs2._
    import fs2.util._
  """,
  doctestWithDependencies := false
)

lazy val root = project.in(file(".")).settings(commonSettings).aggregate(core, io, benchmark)

lazy val core = project.in(file("core")).settings(commonSettings).settings(
 name := "fs2-core"
)

lazy val io = project.in(file("io")).settings(commonSettings).settings(
 name := "fs2-io"
).dependsOn(core % "compile->compile;test->test")

lazy val benchmark = project.in(file("benchmark")).settings(commonSettings).settings(
 name := "fs2-benchmark"
).dependsOn(io)
