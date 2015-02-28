// Temporary project to get this bootstrapped
lazy val shims = project

lazy val core = project.dependsOn(shims)

// This belongs in another repo long term.
lazy val scalaz = project.dependsOn(core)

organization in ThisBuild := "org.stream"

version in ThisBuild := "snapshot-0.7"

scalaVersion in ThisBuild := "2.10.4"

crossScalaVersions in ThisBuild := Seq("2.10.4", "2.11.4")

scalacOptions in ThisBuild ++= Seq(
  "-feature",
  "-deprecation",
  "-language:implicitConversions",
  "-language:higherKinds",
  "-language:existentials",
  "-language:postfixOps",
  // "-Xfatal-warnings", // this makes cross compilation impossible from a single source
  "-Yno-adapted-args"
)

scalacOptions in ThisBuild in (Compile, doc) ++= Seq(
  "-doc-source-url", scmInfo.value.get.browseUrl + "/tree/master€{FILE_PATH}.scala",
  "-sourcepath", baseDirectory.in(LocalRootProject).value.getAbsolutePath
)

resolvers in ThisBuild ++= Seq(Resolver.sonatypeRepo("releases"), Resolver.sonatypeRepo("snapshots"))

libraryDependencies in ThisBuild ++= Seq(
  "org.scodec" %% "scodec-bits" % "1.0.5",
  "org.scalacheck" %% "scalacheck" % "1.12.1" % "test"
)

seq(bintraySettings:_*)

publishMavenStyle in ThisBuild := true

licenses in ThisBuild += ("MIT", url("http://opensource.org/licenses/MIT"))

scmInfo in ThisBuild := Some(ScmInfo(url("https://github.com/scalaz/scalaz-stream"),
  "git@github.com:scalaz/scalaz-stream.git"))

bintray.Keys.packageLabels in bintray.Keys.bintray in ThisBuild :=
  Seq("stream processing", "functional I/O", "iteratees", "functional programming", "scala")

osgiSettings

OsgiKeys.bundleSymbolicName in ThisBuild := "org.stream"

OsgiKeys.exportPackage in ThisBuild := Seq("stream.*")

OsgiKeys.importPackage in ThisBuild := Seq(
  """scala.*;version="$<range;[===,=+);$<@>>"""",
  """scalaz.*;version="$<range;[===,=+);$<@>>"""",
  "*"
)

parallelExecution in Test in ThisBuild := false

autoAPIMappings in ThisBuild := true

apiURL in ThisBuild := Some(url(s"http://docs.typelevel.org/api/scalaz-stream/stable/${version.value}/doc/"))

initialCommands in ThisBuild := "import stream._"

doctestWithDependencies in ThisBuild := false

doctestSettings
