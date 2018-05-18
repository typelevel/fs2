lazy val commonSettings = Seq(
  organization := "co.fs2",
  scalacOptions ++= Seq(
    "-feature",
    "-deprecation",
    "-language:implicitConversions",
    "-language:higherKinds",
    "-language:existentials",
    "-language:postfixOps",
    "-Ypartial-unification"
  ) ++
    (if (scalaBinaryVersion.value.startsWith("2.12"))
       List(
         "-Xlint",
         "-Xfatal-warnings",
         "-Yno-adapted-args",
         "-Ywarn-value-discard",
         "-Ywarn-unused-import"
       )
     else Nil) ++ (if (scalaBinaryVersion.value.startsWith("2.11"))
                     List("-Xexperimental")
                   else
                     Nil), // 2.11 needs -Xexperimental to enable SAM conversion
  scalacOptions in (Compile, console) ~= {
    _.filterNot("-Ywarn-unused-import" == _)
      .filterNot("-Xlint" == _)
      .filterNot("-Xfatal-warnings" == _)
  },
  scalacOptions in (Compile, console) += "-Ydelambdafy:inline",
  scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value,
  libraryDependencies ++= Seq(
    compilerPlugin("org.spire-math" %% "kind-projector" % "0.9.6"),
    "org.typelevel" %% "cats-effect" % "1.0.0-RC",
    "org.typelevel" %% "cats-core" % "1.1.0",
    "org.scalatest" %% "scalatest" % "3.0.5" % "test",
    "org.scalacheck" %% "scalacheck" % "1.13.5" % "test",
    "org.typelevel" %% "cats-laws" % "1.1.0" % "test",
    "org.typelevel" %% "cats-effect-laws" % "1.0.0-RC" % "test"
  ),
  scmInfo := Some(
    ScmInfo(url("https://github.com/functional-streams-for-scala/fs2"),
            "git@github.com:functional-streams-for-scala/fs2.git")),
  homepage := Some(url("https://github.com/functional-streams-for-scala/fs2")),
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  initialCommands := s"""
    import fs2._
    import cats.effect._
    import scala.concurrent.ExecutionContext.Implicits.global, scala.concurrent.duration._
  """,
  scalafmtOnCompile := true
) ++ testSettings

lazy val testSettings = Seq(
  parallelExecution in Test := false,
  testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
  publishArtifact in Test := true
)

lazy val root = project
  .in(file("."))
  .settings(commonSettings)
