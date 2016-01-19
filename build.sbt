val ReleaseTag = """^release/([\d\.]+a?)$""".r

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
  scmInfo := Some(ScmInfo(url("https://github.com/functional-streams-for-scala/fs2"), "git@github.com:functional-streams-for-scala/fs2.git")),
  homepage := Some(url("https://github.com/functional-streams-for-scala/fs2")),
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  initialCommands := s"""
    import fs2._
    import fs2.util._
  """,
  doctestWithDependencies := false
) ++ testSettings ++ scaladocSettings ++ gitSettings

lazy val testSettings = Seq(
  parallelExecution in Test := false,
  logBuffered in Test := false,
  testOptions in Test += Tests.Argument("-verbosity", "2"),
  publishArtifact in Test := true
)

lazy val scaladocSettings = Seq(
  scalacOptions in (Compile, doc) ++= Seq(
    "-doc-source-url", scmInfo.value.get.browseUrl + "/tree/master€{FILE_PATH}.scala",
    "-sourcepath", baseDirectory.in(LocalRootProject).value.getAbsolutePath,
    "-implicits",
    "-implicits-show-all"
  ),
  autoAPIMappings := true
)

lazy val gitSettings = Seq(
  git.baseVersion := "0.9",
  git.gitTagToVersionNumber := {
    case ReleaseTag(version) => Some(version)
    case _ => None
  },
  git.formattedShaVersion := {
    val suffix = git.makeUncommittedSignifierSuffix(git.gitUncommittedChanges.value, git.uncommittedSignifier.value)

    git.gitHeadCommit.value map { _.substring(0, 7) } map { sha =>
      git.baseVersion.value + "-" + sha + suffix
    }
  }
)

lazy val root = project.in(file(".")).
  enablePlugins(GitVersioning).
  settings(commonSettings).
  settings(
    publish := (),
    publishLocal := (),
    publishArtifact := false
  ).
  aggregate(core, io, benchmark)

lazy val core = project.in(file("core")).
  enablePlugins(GitVersioning).
  settings(commonSettings).
  settings(
   name := "fs2-core"
  )

lazy val io = project.in(file("io")).
  enablePlugins(GitVersioning).
  settings(commonSettings).
  settings(
   name := "fs2-io"
  ).dependsOn(core % "compile->compile;test->test")

lazy val benchmark = project.in(file("benchmark")).
  enablePlugins(GitVersioning).
  settings(commonSettings).
  settings(
   name := "fs2-benchmark"
  ).dependsOn(io)
