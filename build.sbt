import microsites.ExtraMdFileConfig
import com.typesafe.tools.mima.core._
import sbtcrossproject.crossProject

addCommandAlias("fmt", "; compile:scalafmt; test:scalafmt; it:scalafmt; scalafmtSbt")
addCommandAlias(
  "fmtCheck",
  "; compile:scalafmtCheck; test:scalafmtCheck; it:scalafmtCheck; scalafmtSbtCheck"
)
addCommandAlias("testJVM", ";coreJVM/test;io/test;reactiveStreams/test;benchmark/test")
addCommandAlias("testJS", "coreJS/test")

ThisBuild / baseVersion := "3.0"

ThisBuild / organization := "co.fs2"
ThisBuild / organizationName := "Functional Streams for Scala"

ThisBuild / homepage := Some(url("https://github.com/typelevel/fs2"))
ThisBuild / startYear := Some(2013)

ThisBuild / crossScalaVersions := Seq("3.0.0-M2", "3.0.0-M3", "2.12.10", "2.13.4")

ThisBuild / versionIntroduced := Map(
  "3.0.0-M2" -> "3.0.0",
  "3.0.0-M3" -> "3.0.0"
)

ThisBuild / githubWorkflowJavaVersions := Seq("adopt@1.11")

ThisBuild / spiewakCiReleaseSnapshots := true

ThisBuild / spiewakMainBranches := List("main", "develop")

ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep.Sbt(List("fmtCheck", "compile")),
  WorkflowStep.Sbt(List("testJVM")),
  WorkflowStep.Sbt(List("testJS")),
  WorkflowStep.Sbt(List("mimaReportBinaryIssues")),
  WorkflowStep.Sbt(List("project coreJVM", "it:test"))
)

ThisBuild / scmInfo := Some(
  ScmInfo(url("https://github.com/typelevel/fs2"), "git@github.com:typelevel/fs2.git")
)

ThisBuild / licenses := List(("MIT", url("http://opensource.org/licenses/MIT")))

ThisBuild / testFrameworks += new TestFramework("munit.Framework")
ThisBuild / doctestTestFramework := DoctestTestFramework.ScalaCheck

ThisBuild / publishGithubUser := "mpilquist"
ThisBuild / publishFullName := "Michael Pilquist"
ThisBuild / developers ++= List(
  "pchiusano" -> "Paul Chiusano",
  "pchlupacek" -> "Pavel Chlupáček",
  "SystemFw" -> "Fabio Labella",
  "alissapajer" -> "Alissa Pajer",
  "djspiewak" -> "Daniel Spiewak",
  "fthomas" -> "Frank Thomas",
  "runarorama" -> "Rúnar Ó. Bjarnason",
  "jedws" -> "Jed Wesley-Smith",
  "durban" -> "Daniel Urban"
).map { case (username, fullName) =>
  Developer(username, fullName, s"@$username", url(s"https://github.com/$username"))
}

ThisBuild / fatalWarningsInCI := false

ThisBuild / Test / javaOptions ++= Seq(
  "-Dscala.concurrent.context.minThreads=8",
  "-Dscala.concurrent.context.numThreads=8",
  "-Dscala.concurrent.context.maxThreads=8"
)
ThisBuild / Test / run / javaOptions ++= Seq("-Xms64m", "-Xmx64m")
ThisBuild / Test / parallelExecution := false

ThisBuild / initialCommands := s"""
    import fs2._, cats.effect._, cats.effect.implicits._, cats.effect.unsafe.implicits.global, cats.syntax.all._, scala.concurrent.duration._
  """

ThisBuild / mimaBinaryIssueFilters ++= Seq(
  // No bincompat on internal package
  ProblemFilters.exclude[Problem]("fs2.internal.*"),
  // Mima reports all ScalaSignature changes as errors, despite the fact that they don't cause bincompat issues when version swapping (see https://github.com/lightbend/mima/issues/361)
  ProblemFilters.exclude[IncompatibleSignatureProblem]("*")
)

lazy val root = project
  .in(file("."))
  .enablePlugins(NoPublishPlugin, SonatypeCiReleasePlugin)
  .aggregate(coreJVM, coreJS, io, reactiveStreams, benchmark)

lazy val IntegrationTest = config("it").extend(Test)

lazy val core = crossProject(JVMPlatform, JSPlatform)
  .in(file("core"))
  .configs(IntegrationTest)
  .settings(Defaults.itSettings: _*)
  .settings(
    inConfig(IntegrationTest)(org.scalafmt.sbt.ScalafmtPlugin.scalafmtConfigSettings)
  )
  .settings(
    name := "fs2-core",
    Compile / scalafmt / unmanagedSources := (Compile / scalafmt / unmanagedSources).value
      .filterNot(_.toString.endsWith("NotGiven.scala")),
    Test / scalafmt / unmanagedSources := (Test / scalafmt / unmanagedSources).value
      .filterNot(_.toString.endsWith("NotGiven.scala"))
  )
  .settings(dottyJsSettings(ThisBuild / crossScalaVersions))
  .settings(
    name := "fs2-core",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core" % "2.3.1",
      "org.typelevel" %%% "cats-laws" % "2.3.1" % Test,
      "org.typelevel" %%% "cats-effect" % "3.0-27-4e31907",
      "org.typelevel" %%% "cats-effect-laws" % "3.0-27-4e31907" % Test,
      "org.typelevel" %%% "cats-effect-testkit" % "3.0-27-4e31907" % Test,
      "org.scodec" %%% "scodec-bits" % "1.1.23",
      "org.typelevel" %%% "scalacheck-effect-munit" % "0.7.0" % Test,
      "org.typelevel" %%% "munit-cats-effect-3" % "0.12.0" % Test
    ),
    Compile / unmanagedSourceDirectories ++= {
      val major = if (isDotty.value) "-3" else "-2"
      List(CrossType.Pure, CrossType.Full).flatMap(
        _.sharedSrcDir(baseDirectory.value, "main").toList.map(f => file(f.getPath + major))
      )
    },
    Test / unmanagedSourceDirectories ++= {
      val major = if (isDotty.value) "-3" else "-2"
      List(CrossType.Pure, CrossType.Full).flatMap(
        _.sharedSrcDir(baseDirectory.value, "test").toList.map(f => file(f.getPath + major))
      )
    }
  )

lazy val coreJVM = core.jvm
  .enablePlugins(SbtOsgi)
  .settings(
    Test / fork := true,
    OsgiKeys.exportPackage := Seq("fs2.*"),
    OsgiKeys.privatePackage := Seq(),
    OsgiKeys.importPackage := {
      val Some((major, minor)) = CrossVersion.partialVersion(scalaVersion.value)
      Seq(s"""scala.*;version="[$major.$minor,$major.${minor + 1})"""", "*")
    },
    OsgiKeys.additionalHeaders := Map("-removeheaders" -> "Include-Resource,Private-Package"),
    osgiSettings
  )

lazy val coreJS = core.js
  .disablePlugins(DoctestPlugin)
  .settings(
    scalaJSStage in Test := FastOptStage,
    jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(),
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )

lazy val io = project
  .in(file("io"))
  .enablePlugins(SbtOsgi)
  .settings(
    name := "fs2-io",
    Test / fork := true,
    OsgiKeys.exportPackage := Seq("fs2.io.*"),
    OsgiKeys.privatePackage := Seq(),
    OsgiKeys.importPackage := {
      val Some((major, minor)) = CrossVersion.partialVersion(scalaVersion.value)
      Seq(
        s"""scala.*;version="[$major.$minor,$major.${minor + 1})"""",
        """fs2.*;version="${Bundle-Version}"""",
        "*"
      )
    },
    OsgiKeys.additionalHeaders := Map("-removeheaders" -> "Include-Resource,Private-Package"),
    osgiSettings
  )
  .dependsOn(coreJVM % "compile->compile;test->test")

lazy val reactiveStreams = project
  .in(file("reactive-streams"))
  .enablePlugins(SbtOsgi)
  .settings(
    name := "fs2-reactive-streams",
    Test / fork := true,
    libraryDependencies ++= Seq(
      "org.reactivestreams" % "reactive-streams" % "1.0.3",
      "org.reactivestreams" % "reactive-streams-tck" % "1.0.3" % "test",
      ("org.scalatestplus" %% "testng-6-7" % "3.2.3.0" % "test").withDottyCompat(scalaVersion.value)
    ),
    OsgiKeys.exportPackage := Seq("fs2.interop.reactivestreams.*"),
    OsgiKeys.privatePackage := Seq(),
    OsgiKeys.importPackage := {
      val Some((major, minor)) = CrossVersion.partialVersion(scalaVersion.value)
      Seq(
        s"""scala.*;version="[$major.$minor,$major.${minor + 1})"""",
        """fs2.*;version="${Bundle-Version}"""",
        "*"
      )
    },
    OsgiKeys.additionalHeaders := Map("-removeheaders" -> "Include-Resource,Private-Package"),
    osgiSettings
  )
  .dependsOn(coreJVM % "compile->compile;test->test")

lazy val benchmark = project
  .in(file("benchmark"))
  .enablePlugins(JmhPlugin, NoPublishPlugin)
  .settings(
    name := "fs2-benchmark",
    Test / run / javaOptions := (Test / run / javaOptions).value
      .filterNot(o => o.startsWith("-Xmx") || o.startsWith("-Xms")) ++ Seq("-Xms256m", "-Xmx256m")
  )
  .dependsOn(io)

lazy val microsite = project
  .in(file("site"))
  .enablePlugins(MicrositesPlugin, NoPublishPlugin)
  .settings(
    micrositeName := "fs2",
    micrositeDescription := "Purely functional, effectful, resource-safe, concurrent streams for Scala",
    micrositeGithubOwner := "typelevel",
    micrositeGithubRepo := "fs2",
    micrositePushSiteWith := GitHub4s,
    micrositeGithubToken := sys.env.get("GITHUB_TOKEN"),
    micrositeBaseUrl := "",
    micrositeHighlightTheme := "atom-one-light",
    micrositeExtraMdFilesOutput := resourceManaged.value / "main" / "jekyll",
    micrositeExtraMdFiles := Map(
      file("README.md") -> ExtraMdFileConfig(
        "index.md",
        "home",
        Map("title" -> "Home", "section" -> "home", "position" -> "0")
      )
    ),
    scalacOptions in Compile ~= {
      _.filterNot("-Ywarn-unused-import" == _)
        .filterNot("-Ywarn-unused" == _)
        .filterNot("-Xlint" == _)
        .filterNot("-Xfatal-warnings" == _)
    },
    scalacOptions in Compile += "-Ydelambdafy:inline",
    githubWorkflowArtifactUpload := false
  )
  .dependsOn(coreJVM, io, reactiveStreams)
