enablePlugins(GitVersioning)

organization := "org.scalaz.stream"

name := "scalaz-stream"

val ReleaseTag = """^release/([\d\.]+a?)$""".r
git.gitTagToVersionNumber := {
  case ReleaseTag(version) => Some(version)
  case _ => None
}

scalaVersion := "2.11.6"

crossScalaVersions := Seq("2.10.5", "2.11.6")

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
  "org.scalaz" %% "scalaz-core" % "7.1.2",
  "org.scalaz" %% "scalaz-concurrent" % "7.1.2",
  "org.scodec" %% "scodec-bits" % "1.0.6",
  "org.scalaz" %% "scalaz-scalacheck-binding" % "7.1.2" % "test",
  "org.scalacheck" %% "scalacheck" % "1.12.2" % "test"
)

seq(bintraySettings:_*)

publishMavenStyle := true

licenses += ("MIT", url("http://opensource.org/licenses/MIT"))

scmInfo := Some(ScmInfo(url("https://github.com/scalaz/scalaz-stream"),
  "git@github.com:scalaz/scalaz-stream.git"))

bintray.Keys.packageLabels in bintray.Keys.bintray :=
  Seq("stream processing", "functional I/O", "iteratees", "functional programming", "scala")

osgiSettings

OsgiKeys.bundleSymbolicName := "org.scalaz.stream"

OsgiKeys.exportPackage := Seq("scalaz.stream.*")

OsgiKeys.importPackage := Seq(
  """scala.*;version="$<range;[===,=+);$<@>>"""",
  """scalaz.*;version="$<range;[===,=+);$<@>>"""",
  "*"
)

parallelExecution in Test := false

autoAPIMappings := true

apiURL := Some(url(s"http://docs.typelevel.org/api/scalaz-stream/stable/${version.value}/doc/"))

initialCommands := "import scalaz.stream._"

doctestWithDependencies := false

doctestSettings
