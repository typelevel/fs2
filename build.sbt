enablePlugins(GitVersioning)

organization := "org.scalaz.stream"

name := "scalaz-stream"

val ReleaseTag = """^release/([\d\.]+a?)$""".r
git.gitTagToVersionNumber := {
  case ReleaseTag(version) => Some(version)
  case _ => None
}

scalaVersion := "2.11.7"

crossScalaVersions := Seq("2.10.5", "2.11.7", "2.12.0-M1")

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
  "org.scodec" %% "scodec-bits" % "1.0.9",
  "org.scalaz" %% "scalaz-scalacheck-binding" % "7.1.2" % "test",
  "org.scalacheck" %% "scalacheck" % "1.12.4" % "test"
)

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
