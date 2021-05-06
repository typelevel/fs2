import com.typesafe.tools.mima.core._
import sbtcrossproject.crossProject

addCommandAlias("fmt", "; compile:scalafmt; test:scalafmt; it:scalafmt; scalafmtSbt")
addCommandAlias(
  "fmtCheck",
  "; compile:scalafmtCheck; test:scalafmtCheck; it:scalafmtCheck; scalafmtSbtCheck"
)
addCommandAlias("testJVM", ";coreJVM/test;io/test;reactiveStreams/test;benchmark/test")
addCommandAlias("testJS", "coreJS/test")

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / baseVersion := "3.0"

ThisBuild / organization := "co.fs2"
ThisBuild / organizationName := "Functional Streams for Scala"

ThisBuild / homepage := Some(url("https://github.com/typelevel/fs2"))
ThisBuild / startYear := Some(2013)

ThisBuild / crossScalaVersions := Seq("3.0.0-RC2", "3.0.0-RC3", "2.12.13", "2.13.5")

ThisBuild / githubWorkflowJavaVersions := Seq("adopt@1.11")

ThisBuild / spiewakCiReleaseSnapshots := true

ThisBuild / spiewakMainBranches := List("main", "series/2.5.x")

ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep.Sbt(List("fmtCheck", "test", "mimaReportBinaryIssues"))
  // WorkflowStep.Sbt(List("coreJVM/it:test")) // Memory leak tests fail intermittently on CI
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
  ProblemFilters.exclude[IncompatibleSignatureProblem]("*"),
  ProblemFilters.exclude[IncompatibleMethTypeProblem]("fs2.Pull#MapOutput.apply"),
  ProblemFilters.exclude[IncompatibleResultTypeProblem]("fs2.Pull#MapOutput.fun"),
  ProblemFilters.exclude[IncompatibleMethTypeProblem]("fs2.Pull#MapOutput.copy"),
  ProblemFilters.exclude[IncompatibleResultTypeProblem]("fs2.Pull#MapOutput.copy$default$2"),
  ProblemFilters.exclude[IncompatibleMethTypeProblem]("fs2.Pull#MapOutput.this")
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
      "org.typelevel" %%% "cats-core" % "2.6.0",
      "org.typelevel" %%% "cats-laws" % "2.6.0" % Test,
      "org.typelevel" %%% "cats-effect-std" % "3.1-106-5227279",
      "org.typelevel" %%% "cats-effect-sync" % "3.1-106-5227279",
      "org.typelevel" %%% "cats-effect-laws" % "3.1-106-5227279" % Test,
      "org.typelevel" %%% "cats-effect-testkit" % "3.1-106-5227279" % Test,
      "org.scodec" %%% "scodec-bits" % "1.1.26",
      "org.typelevel" %%% "scalacheck-effect-munit" % "1.0.1" % Test,
      "org.typelevel" %%% "munit-cats-effect-3" % "1.0.2" % Test,
      "org.typelevel" %%% "discipline-munit" % "1.0.8" % Test
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
    libraryDependencies += "com.comcast" %% "ip4s-core" % "3.0.2",
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
      ("org.scalatestplus" %% "testng-6-7" % "3.2.8.0" % "test").withDottyCompat(scalaVersion.value)
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
  .in(file("mdoc"))
  .settings(
    mdocIn := file("site"),
    mdocOut := file("target/website"),
    mdocVariables := Map(
      "version" -> version.value,
      "scalaVersions" -> crossScalaVersions.value
        .map(v => s"- **$v**")
        .mkString("\n")
    ),
    githubWorkflowArtifactUpload := false,
    fatalWarningsInCI := false
  )
  .dependsOn(coreJVM, io, reactiveStreams)
  .enablePlugins(MdocPlugin, NoPublishPlugin)

ThisBuild / githubWorkflowBuildPostamble ++= List(
  WorkflowStep.Sbt(
    List("microsite/mdoc"),
    cond = Some(s"matrix.scala == '2.13.5'")
  )
)

ThisBuild / githubWorkflowAddedJobs += WorkflowJob(
  id = "site",
  name = "Deploy site",
  needs = List("publish"),
  javas = (ThisBuild / githubWorkflowJavaVersions).value.toList,
  scalas = (ThisBuild / scalaVersion).value :: Nil,
  cond = """
  | always() &&
  | needs.build.result == 'success' &&
  | (needs.publish.result == 'success' && github.ref == 'refs/heads/main')
  """.stripMargin.trim.linesIterator.mkString.some,
  steps = githubWorkflowGeneratedDownloadSteps.value.toList :+
    WorkflowStep.Use(
      UseRef.Public("peaceiris", "actions-gh-pages", "v3"),
      name = Some(s"Deploy site"),
      params = Map(
        "publish_dir" -> "./target/website",
        "github_token" -> "${{ secrets.GITHUB_TOKEN }}"
      )
    )
)
