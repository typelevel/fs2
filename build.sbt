import microsites.ExtraMdFileConfig
import com.typesafe.tools.mima.core._
import sbtrelease.Version
import sbtcrossproject.crossProject

val ReleaseTag = """^release/([\d\.]+a?)$""".r

addCommandAlias("fmt", "; compile:scalafmt; test:scalafmt; it:scalafmt; scalafmtSbt")
addCommandAlias(
  "fmtCheck",
  "; compile:scalafmtCheck; test:scalafmtCheck; it:scalafmtCheck; scalafmtSbtCheck"
)

crossScalaVersions in ThisBuild := Seq("2.13.2", "2.12.10")
scalaVersion in ThisBuild := crossScalaVersions.value.head

githubWorkflowJavaVersions in ThisBuild := Seq("adopt@1.11")
githubWorkflowPublishTargetBranches in ThisBuild := Seq(RefPredicate.Equals(Ref.Branch("master")))
githubWorkflowBuild in ThisBuild := WorkflowStep.Sbt(
  List(
    "fmtCheck",
    "compile",
    "testJVM",
    "testJS",
    "doc",
    "mimaReportBinaryIssues",
    ";project coreJVM;it:test"
  )
)
githubWorkflowEnv in ThisBuild ++= Map(
  "SONATYPE_USERNAME" -> "fs2-ci",
  "SONATYPE_PASSWORD" -> s"$${{ secrets.SONATYPE_PASSWORD }}"
)

lazy val contributors = Seq(
  "pchiusano" -> "Paul Chiusano",
  "pchlupacek" -> "Pavel Chlupáček",
  "SystemFw" -> "Fabio Labella",
  "alissapajer" -> "Alissa Pajer",
  "djspiewak" -> "Daniel Spiewak",
  "fthomas" -> "Frank Thomas",
  "runarorama" -> "Rúnar Ó. Bjarnason",
  "jedws" -> "Jed Wesley-Smith",
  "mpilquist" -> "Michael Pilquist",
  "durban" -> "Daniel Urban"
)

lazy val commonSettingsBase = Seq(
  organization := "co.fs2",
  scalacOptions ++= Seq(
    "-feature",
    "-deprecation",
    "-language:implicitConversions",
    "-language:higherKinds",
    "-Xfatal-warnings"
  ) ++
    (scalaBinaryVersion.value match {
      case v if v.startsWith("2.13") =>
        List("-Xlint", "-Ywarn-unused")
      case v if v.startsWith("2.12") =>
        List("-Ypartial-unification")
      case other => sys.error(s"Unsupported scala version: $other")
    }),
  scalacOptions in (Compile, console) ~= {
    _.filterNot("-Ywarn-unused" == _)
      .filterNot("-Xlint" == _)
      .filterNot("-Xfatal-warnings" == _)
  },
  // Disable fatal warnings for test compilation because sbt-doctest generated tests
  // generate warnings which lead to test failures.
  scalacOptions in (Test, compile) ~= {
    _.filterNot("-Xfatal-warnings" == _)
  },
  scalacOptions in (Compile, console) += "-Ydelambdafy:inline",
  scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value,
  javaOptions in (Test, run) ++= Seq("-Xms64m", "-Xmx64m"),
  libraryDependencies ++= Seq(
    compilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3"),
    "org.typelevel" %%% "cats-core" % "2.1.1",
    "org.typelevel" %%% "cats-laws" % "2.1.1" % "test",
    "org.typelevel" %%% "cats-effect" % "2.1.3",
    "org.typelevel" %%% "cats-effect-laws" % "2.1.3" % "test",
    "org.scalacheck" %%% "scalacheck" % "1.14.3" % "test",
    "org.scalatest" %%% "scalatest" % "3.3.0-SNAP2" % "test",
    "org.scalatestplus" %%% "scalacheck-1-14" % "3.1.2.0" % "test"
  ),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/functional-streams-for-scala/fs2"),
      "git@github.com:functional-streams-for-scala/fs2.git"
    )
  ),
  homepage := Some(url("https://github.com/functional-streams-for-scala/fs2")),
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  initialCommands := s"""
    import fs2._, cats.effect._, cats.effect.implicits._, cats.implicits._
    import scala.concurrent.ExecutionContext.Implicits.global, scala.concurrent.duration._
    implicit val contextShiftIO: ContextShift[IO] = IO.contextShift(global)
    implicit val timerIO: Timer[IO] = IO.timer(global)
  """,
  doctestTestFramework := DoctestTestFramework.ScalaTest
) ++ scaladocSettings ++ publishingSettings ++ releaseSettings

lazy val commonSettings = commonSettingsBase ++ testSettings
lazy val crossCommonSettings = commonSettingsBase ++ crossTestSettings

lazy val commonTestSettings = Seq(
  javaOptions in Test ++= (Seq(
    "-Dscala.concurrent.context.minThreads=8",
    "-Dscala.concurrent.context.numThreads=8",
    "-Dscala.concurrent.context.maxThreads=8"
  ) ++ (sys.props.get("fs2.test.travis") match {
    case Some(value) =>
      Seq(s"-Dfs2.test.travis=true")
    case None => Seq()
  })),
  parallelExecution in Test := false,
  testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
  publishArtifact in Test := true
)
lazy val testSettings =
  (fork in Test := true) +:
    commonTestSettings

lazy val crossTestSettings =
  (fork in Test := crossProjectPlatform.value != JSPlatform) +:
    commonTestSettings

lazy val mdocSettings = Seq(
  scalacOptions in Compile ~= {
    _.filterNot("-Ywarn-unused-import" == _)
      .filterNot("-Ywarn-unused" == _)
      .filterNot("-Xlint" == _)
      .filterNot("-Xfatal-warnings" == _)
  },
  scalacOptions in Compile += "-Ydelambdafy:inline"
)

def scmBranch(v: String): String = {
  val Some(ver) = Version(v)
  if (ver.qualifier.exists(_ == "-SNAPSHOT"))
    // support branch (0.9.0-SNAPSHOT -> series/0.9)
    s"series/${ver.copy(subversions = ver.subversions.take(1), qualifier = None).string}"
  else
    // release tag (0.9.0-M2 -> v0.9.0-M2)
    s"v${ver.string}"
}

lazy val scaladocSettings = Seq(
  scalacOptions in (Compile, doc) ++= Seq(
    "-doc-source-url",
    s"${scmInfo.value.get.browseUrl}/tree/${scmBranch(version.value)}€{FILE_PATH}.scala",
    "-sourcepath",
    baseDirectory.in(LocalRootProject).value.getAbsolutePath,
    "-implicits",
    "-implicits-sound-shadowing",
    "-implicits-show-all"
  ),
  scalacOptions in (Compile, doc) ~= { _.filterNot(_ == "-Xfatal-warnings") },
  autoAPIMappings := true
)

lazy val publishingSettings = Seq(
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (version.value.trim.endsWith("SNAPSHOT"))
      Some("snapshots".at(nexus + "content/repositories/snapshots"))
    else
      Some("releases".at(nexus + "service/local/staging/deploy/maven2"))
  },
  credentials ++= (for {
    username <- Option(System.getenv().get("SONATYPE_USERNAME"))
    password <- Option(System.getenv().get("SONATYPE_PASSWORD"))
  } yield Credentials(
    "Sonatype Nexus Repository Manager",
    "oss.sonatype.org",
    username,
    password
  )).toSeq,
  publishMavenStyle := true,
  pomIncludeRepository := { _ => false },
  pomExtra := {
    <developers>
      {
      for ((username, name) <- contributors) yield <developer>
        <id>{username}</id>
        <name>{name}</name>
        <url>http://github.com/{username}</url>
      </developer>
    }
    </developers>
  },
  pomPostProcess := { node =>
    import scala.xml._
    import scala.xml.transform._
    def stripIf(f: Node => Boolean) =
      new RewriteRule {
        override def transform(n: Node) =
          if (f(n)) NodeSeq.Empty else n
      }
    val stripTestScope = stripIf(n => n.label == "dependency" && (n \ "scope").text == "test")
    new RuleTransformer(stripTestScope).transform(node)(0)
  },
  gpgWarnOnFailure := Option(System.getenv().get("GPG_WARN_ON_FAILURE")).isDefined
)

lazy val commonJsSettings = Seq(
  scalaJSLinkerConfig ~= { config =>
    // https://github.com/scala-js/scala-js/issues/2798
    try {
      scala.util.Properties.isJavaAtLeast("1.8")
      config
    } catch {
      case _: NumberFormatException =>
        config.withParallel(false)
    }
  },
  scalaJSStage in Test := FastOptStage,
  jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(),
  scalacOptions in Compile += {
    val dir = project.base.toURI.toString.replaceFirst("[^/]+/?$", "")
    val url =
      "https://raw.githubusercontent.com/functional-streams-for-scala/fs2"
    s"-P:scalajs:mapSourceURI:$dir->$url/${scmBranch(version.value)}/"
  }
)

lazy val noPublish = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false
)

lazy val releaseSettings = Seq(
  releaseCrossBuild := true
)

lazy val mimaSettings = Seq(
  mimaPreviousArtifacts := {
    List("2.0.0", "2.3.0").map { pv =>
      organization.value % (normalizedName.value + "_" + scalaBinaryVersion.value) % pv
    }.toSet
  },
  mimaBinaryIssueFilters ++= Seq(
    // These methods were only used internally between Stream and Pull: they were private to fs2.
    ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.Stream.fromFreeC"),
    ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.Stream.get$extension"),
    ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.Stream#IdOps.self$extension"),
    ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.Pull.get$extension"),
    ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.Pull.get"),
    ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.Stream.get$extension"),
    ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.Stream.get"),
    ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.Pull.fromFreeC"),
    ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.Pull.get$extension"),
    // No bincompat on internal package
    ProblemFilters.exclude[Problem]("fs2.internal.*"),
    // Mima reports all ScalaSignature changes as errors, despite the fact that they don't cause bincompat issues when version swapping (see https://github.com/lightbend/mima/issues/361)
    ProblemFilters.exclude[IncompatibleSignatureProblem]("*"),
    // .to(sink) syntax was removed in 1.0.2 and has been hidden in all 2.x releases behind private[fs2], hence it's safe to remove
    ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.Stream.to"),
    ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.Stream.to$extension"),
    ProblemFilters.exclude[DirectMissingMethodProblem](
      "fs2.interop.reactivestreams.StreamSubscriber#FSM.stream"
    ), // FSM is package private
    ProblemFilters.exclude[Problem]("fs2.io.tls.TLSEngine.*"), // private[fs2] type
    ProblemFilters.exclude[Problem]("fs2.io.tls.TLSEngine#*"),
    ProblemFilters.exclude[DirectMissingMethodProblem](
      "fs2.io.tls.TLSSocket.fs2$io$tls$TLSSocket$$binding$default$2"
    ),
    ProblemFilters.exclude[DirectMissingMethodProblem](
      "fs2.io.tls.TLSSocket.fs2$io$tls$TLSSocket$$binding$default$3"
    ),
    // InputOutputBuffer is private[tls]
    ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.io.tls.InputOutputBuffer.output"),
    ProblemFilters.exclude[ReversedMissingMethodProblem]("fs2.io.tls.InputOutputBuffer.output")
  )
)

lazy val root = project
  .in(file("."))
  .disablePlugins(MimaPlugin)
  .settings(commonSettings)
  .settings(mimaSettings)
  .settings(noPublish)
  .aggregate(coreJVM, coreJS, io, reactiveStreams, benchmark, experimental, microsite)

lazy val IntegrationTest = config("it").extend(Test)

lazy val core = crossProject(JVMPlatform, JSPlatform)
  .in(file("core"))
  .configs(IntegrationTest)
  .settings(Defaults.itSettings: _*)
  .settings(
    testOptions in IntegrationTest := Seq(Tests.Argument(TestFrameworks.ScalaTest, "-oDF")),
    inConfig(IntegrationTest)(org.scalafmt.sbt.ScalafmtPlugin.scalafmtConfigSettings)
  )
  .settings(crossCommonSettings: _*)
  .settings(
    name := "fs2-core",
    sourceDirectories in (Compile, scalafmt) += baseDirectory.value / "../shared/src/main/scala",
    libraryDependencies += "org.scodec" %%% "scodec-bits" % "1.1.16"
  )
  .jsSettings(commonJsSettings: _*)

lazy val coreJVM = core.jvm
  .enablePlugins(SbtOsgi)
  .settings(
    OsgiKeys.exportPackage := Seq("fs2.*"),
    OsgiKeys.privatePackage := Seq(),
    OsgiKeys.importPackage := {
      val Some((major, minor)) = CrossVersion.partialVersion(scalaVersion.value)
      Seq(s"""scala.*;version="[$major.$minor,$major.${minor + 1})"""", "*")
    },
    OsgiKeys.additionalHeaders := Map("-removeheaders" -> "Include-Resource,Private-Package"),
    osgiSettings
  )
  .settings(mimaSettings)

lazy val coreJS = core.js.disablePlugins(DoctestPlugin, MimaPlugin)

lazy val io = project
  .in(file("io"))
  .enablePlugins(SbtOsgi)
  .settings(commonSettings)
  .settings(mimaSettings)
  .settings(
    name := "fs2-io",
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
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.reactivestreams" % "reactive-streams" % "1.0.3",
      "org.reactivestreams" % "reactive-streams-tck" % "1.0.3" % "test",
      "org.scalatestplus" %% "scalatestplus-testng" % "1.0.0-M2" % "test"
    )
  )
  .settings(mimaSettings)
  .settings(
    name := "fs2-reactive-streams",
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
  .disablePlugins(MimaPlugin)
  .settings(commonSettings)
  .settings(noPublish)
  .settings(
    name := "fs2-benchmark",
    javaOptions in (Test, run) := (javaOptions in (Test, run)).value
      .filterNot(o => o.startsWith("-Xmx") || o.startsWith("-Xms")) ++ Seq("-Xms256m", "-Xmx256m")
  )
  .enablePlugins(JmhPlugin)
  .dependsOn(io)

lazy val microsite = project
  .in(file("site"))
  .enablePlugins(MicrositesPlugin)
  .disablePlugins(MimaPlugin)
  .settings(commonSettings)
  .settings(noPublish)
  .settings(
    micrositeName := "fs2",
    micrositeDescription := "Purely functional, effectful, resource-safe, concurrent streams for Scala",
    micrositeGithubOwner := "functional-streams-for-scala",
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
    )
  )
  .settings(mdocSettings)
  .dependsOn(coreJVM, io, reactiveStreams)

lazy val experimental = project
  .in(file("experimental"))
  .enablePlugins(SbtOsgi)
  .settings(commonSettings)
  .settings(mimaSettings)
  .settings(
    name := "fs2-experimental",
    OsgiKeys.exportPackage := Seq("fs2.experimental.*"),
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

addCommandAlias("testJVM", ";coreJVM/test;io/test;reactiveStreams/test;benchmark/test")
addCommandAlias("testJS", "coreJS/test")
