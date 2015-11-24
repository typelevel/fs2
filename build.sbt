enablePlugins(GitVersioning)

organization := "org.scalaz.stream"

name := "scalaz-stream"

// this is equivalent to declaring compatibility checks
git.baseVersion := "0.8"

val ReleaseTag = """^release/([\d\.]+a?)$""".r
git.gitTagToVersionNumber := {
  case ReleaseTag(version) => Some(version)
  case _ => None
}

git.formattedShaVersion := {
  val suffix = git.makeUncommittedSignifierSuffix(git.gitUncommittedChanges.value, git.uncommittedSignifier.value)

  git.gitHeadCommit.value map { _.substring(0, 7) } map { sha =>
    git.baseVersion.value + "-" + sha + suffix
  }
}

scalaVersion := "2.11.7"

crossScalaVersions := Seq("2.11.7", "2.12.0-M2")

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-language:implicitConversions",
  "-language:higherKinds",
  "-language:existentials",
  "-language:postfixOps",
  // "-Xfatal-warnings", // this makes cross compilation impossible from a single source
  "-Yno-adapted-args"
)

scalacOptions in (Compile, doc) ++= Seq(
  "-doc-source-url", scmInfo.value.get.browseUrl + "/tree/master€{FILE_PATH}.scala",
  "-sourcepath", baseDirectory.in(LocalRootProject).value.getAbsolutePath,
  "-implicits",
  "-implicits-show-all"
)

resolvers ++= Seq(Resolver.sonatypeRepo("releases"), Resolver.sonatypeRepo("snapshots"))

libraryDependencies ++= Seq(
  "org.scalaz" %% "scalaz-core" % "7.1.4",
  "org.scalaz" %% "scalaz-concurrent" % "7.1.4",
  "org.scodec" %% "scodec-bits" % "1.0.9",
  "org.scalaz" %% "scalaz-scalacheck-binding" % "7.1.4" % "test",
  "org.scalacheck" %% "scalacheck" % "1.12.5" % "test"
)

sonatypeProfileName := "org.scalaz"

publishMavenStyle := true

pomIncludeRepository := { _ => false }

pomExtra :=
  <developers>
    <developer>
      <id>pchiusano</id>
      <name>Paul Chiusano</name>
      <url>https://pchiusano.github.io</url>
    </developer>
    <developer>
      <id>pchlupacek</id>
      <name>Pavel Chlupáček</name>
    </developer>
    <developer>
      <id>djspiewak</id>
      <name>Daniel Spiewak</name>
      <url>http://www.codecommit.com</url>
    </developer>
    <developer>
      <id>alissapajer</id>
      <name>Alissa Pajer</name>
    </developer>
    <developer>
      <id>fthomas</id>
      <name>Frank S. Thomas</name>
    </developer>
    <developer>
      <id>runarorama</id>
      <name>Rúnar Ó. Bjarnason</name>
    </developer>
    <developer>
      <id>jedws</id>
      <name>Jed Wesley-Smith</name>
    </developer>
  </developers>

homepage := Some(url("https://github.com/scalaz/scalaz-stream"))

licenses += ("MIT", url("http://opensource.org/licenses/MIT"))

scmInfo := Some(ScmInfo(url("https://github.com/scalaz/scalaz-stream"),
  "git@github.com:scalaz/scalaz-stream.git"))

osgiSettings

OsgiKeys.bundleSymbolicName := "fs2"

OsgiKeys.exportPackage := Seq("fs2.*")

OsgiKeys.importPackage := Seq(
  """scala.*;version="$<range;[===,=+);$<@>>"""",
  "*"
)

val ignoredABIProblems = {
  import com.typesafe.tools.mima.core._
  import com.typesafe.tools.mima.core.ProblemFilters._

  Seq()
}

lazy val mimaSettings = {
  import com.typesafe.tools.mima.plugin.MimaKeys.{binaryIssueFilters, previousArtifact}
  import com.typesafe.tools.mima.plugin.MimaPlugin.mimaDefaultSettings

  mimaDefaultSettings ++ Seq(
    previousArtifact := MiMa.targetVersion(git.baseVersion.value).map(organization.value %% name.value % _),
    binaryIssueFilters ++= ignoredABIProblems
  )
}

mimaSettings

parallelExecution in Test := false

logBuffered in Test := false

testOptions in Test += Tests.Argument("-verbosity", "2")

autoAPIMappings := true

initialCommands := s"""
  import fs2._
  import fs2.util._
"""

doctestWithDependencies := false

doctestSettings
