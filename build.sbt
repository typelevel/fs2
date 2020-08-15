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

crossScalaVersions in ThisBuild := Seq("2.13.2", "2.12.10", "0.26.0-RC1")
scalaVersion in ThisBuild := crossScalaVersions.value.head

githubWorkflowJavaVersions in ThisBuild := Seq("adopt@1.11")
githubWorkflowPublishTargetBranches in ThisBuild := Seq(RefPredicate.Equals(Ref.Branch("main")))
githubWorkflowBuild in ThisBuild := Seq(
  WorkflowStep.Sbt(List("fmtCheck", "compile")),
  WorkflowStep.Sbt(List("testJVM")),
  WorkflowStep.Sbt(List("testJS")),
  WorkflowStep.Sbt(List("doc", "mimaReportBinaryIssues")),
  WorkflowStep.Sbt(List(";project coreJVM;it:test"))
)
githubWorkflowEnv in ThisBuild ++= Map(
  "SONATYPE_USERNAME" -> "fs2-ci",
  "SONATYPE_PASSWORD" -> s"$${{ secrets.SONATYPE_PASSWORD }}"
)

def withoutTargetPredicate(step: WorkflowStep): Boolean =
  step match {
    case step: WorkflowStep.Use => step.params("path").startsWith("site")
    case _                      => false
  }

ThisBuild / githubWorkflowGeneratedUploadSteps :=
  (ThisBuild / githubWorkflowGeneratedUploadSteps).value.filterNot(withoutTargetPredicate)

ThisBuild / githubWorkflowGeneratedDownloadSteps :=
  (ThisBuild / githubWorkflowGeneratedDownloadSteps).value.filterNot(withoutTargetPredicate)

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
    "-Xfatal-warnings"
  ) ++
    (scalaBinaryVersion.value match {
      case v if v.startsWith("2.13") =>
        List("-Xlint", "-Ywarn-unused", "-language:implicitConversions,higherKinds")
      case v if v.startsWith("2.12") =>
        List("-Ypartial-unification", "-language:implicitConversions,higherKinds")
      case v if v.startsWith("0.") =>
        List("-Ykind-projector", "-language:implicitConversions,higherKinds")
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
  scalacOptions in (Compile, console) ++= {
    if (isDotty.value) Nil else Seq("-Ydelambdafy:inline")
  },
  scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value,
  javaOptions in (Test, run) ++= Seq("-Xms64m", "-Xmx64m"),
  libraryDependencies ++= Seq(
    ("org.typelevel" %%% "cats-core" % "2.2.0-RC3").withDottyCompat(scalaVersion.value),
    ("org.typelevel" %%% "cats-laws" % "2.2.0-RC3" % "test").withDottyCompat(scalaVersion.value),
    ("org.typelevel" %%% "cats-effect" % "2.2.0-RC3").withDottyCompat(scalaVersion.value),
    ("org.typelevel" %%% "cats-effect-laws" % "2.2.0-RC3" % "test")
      .withDottyCompat(scalaVersion.value),
    "org.typelevel" %%% "scalacheck-effect-munit" % "0.0.3" % "test",
    "org.typelevel" %%% "munit-cats-effect" % "0.0-ec56350" % "test"
  ),
  libraryDependencies ++= {
    if (isDotty.value) Nil
    else
      Seq(
        compilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3")
      )
  },
  testFrameworks += new TestFramework("munit.Framework"),
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
  doctestTestFramework := DoctestTestFramework.ScalaCheck
) ++ scaladocSettings ++ publishingSettings ++ releaseSettings

lazy val commonSettings = commonSettingsBase ++ testSettings
lazy val crossCommonSettings = commonSettingsBase ++ crossTestSettings

lazy val commonTestSettings = Seq(
  javaOptions in Test ++= (Seq(
    "-Dscala.concurrent.context.minThreads=8",
    "-Dscala.concurrent.context.numThreads=8",
    "-Dscala.concurrent.context.maxThreads=8"
  )),
  parallelExecution in Test := false,
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
  scalacOptions in (Compile, doc) ++= {
    if (isDotty.value) Nil
    else
      Seq(
        "-doc-source-url",
        s"${scmInfo.value.get.browseUrl}/tree/${scmBranch(version.value)}€{FILE_PATH}.scala",
        "-sourcepath",
        baseDirectory.in(LocalRootProject).value.getAbsolutePath,
        "-implicits",
        "-implicits-sound-shadowing",
        "-implicits-show-all"
      )
  },
  scalacOptions in (Compile, doc) ~= { _.filterNot(_ == "-Xfatal-warnings") },
  autoAPIMappings := true,
  Compile / doc / sources := {
    val old = (Compile / doc / sources).value
    if (isDotty.value)
      Seq()
    else
      old
  },
  Test / doc / sources := {
    val old = (Test / doc / sources).value
    if (isDotty.value)
      Seq()
    else
      old
  }
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
      for ((username, name) <- contributors)
        yield <developer>
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
  gpgWarnOnFailure := version.value.endsWith("SNAPSHOT")
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
  },
  scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
)

lazy val noPublish = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false
)

lazy val releaseSettings = Seq(
  releaseCrossBuild := true,
  releaseProcess := {
    import sbtrelease.ReleaseStateTransformations._
    Seq[ReleaseStep](
      inquireVersions,
      runClean,
      releaseStepCommandAndRemaining("+test"),
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      releaseStepCommandAndRemaining("+publish"),
      setNextVersion,
      commitNextVersion,
      pushChanges
    )
  },
  publishConfiguration := publishConfiguration.value.withOverwrite(true)
)

lazy val mimaSettings = Seq(
  mimaPreviousArtifacts := {
    if (isDotty.value) Set.empty
    else
      List("2.0.0", "2.3.0", "2.4.2").map { pv =>
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
    ProblemFilters.exclude[ReversedMissingMethodProblem]("fs2.io.tls.InputOutputBuffer.output"),
    // Private traits for implicit prioritization
    ProblemFilters.exclude[ReversedMissingMethodProblem](
      "fs2.Stream#LowPrioCompiler.fs2$Stream$LowPrioCompiler$_setter_$fallibleInstance_="
    ),
    ProblemFilters.exclude[ReversedMissingMethodProblem](
      "fs2.Stream#LowPrioCompiler.fallibleInstance"
    ),
    ProblemFilters.exclude[InheritedNewAbstractMethodProblem](
      "fs2.Stream#LowPrioCompiler.fs2$Stream$LowPrioCompiler1$_setter_$idInstance_="
    ),
    ProblemFilters.exclude[InheritedNewAbstractMethodProblem](
      "fs2.Stream#LowPrioCompiler.idInstance"
    )
  )
)

lazy val root = project
  .in(file("."))
  .disablePlugins(MimaPlugin)
  .settings(commonSettings)
  .settings(mimaSettings)
  .settings(noPublish)
  .aggregate(coreJVM, coreJS, io, reactiveStreams, benchmark, experimental)

lazy val IntegrationTest = config("it").extend(Test)

lazy val core = crossProject(JVMPlatform, JSPlatform)
  .in(file("core"))
  .configs(IntegrationTest)
  .settings(Defaults.itSettings: _*)
  .settings(
    inConfig(IntegrationTest)(org.scalafmt.sbt.ScalafmtPlugin.scalafmtConfigSettings)
  )
  .settings(crossCommonSettings: _*)
  .settings(
    name := "fs2-core",
    sourceDirectories in (Compile, scalafmt) += baseDirectory.value / "../shared/src/main/scala",
    Compile / unmanagedSourceDirectories ++= {
      if (isDotty.value)
        List(CrossType.Pure, CrossType.Full).flatMap(
          _.sharedSrcDir(baseDirectory.value, "main").toList.map(f => file(f.getPath + "-3"))
        )
      else Nil
    },
    crossScalaVersions := {
      val default = crossScalaVersions.value
      if (crossProjectPlatform.value.identifier != "jvm")
        default.filter(_.startsWith("2."))
      else
        default
    },
    libraryDependencies += "org.scodec" %%% "scodec-bits" % "1.1.18"
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
    Compile / unmanagedSourceDirectories ++= {
      if (isDotty.value)
        List(CrossType.Pure, CrossType.Full).flatMap(
          _.sharedSrcDir(baseDirectory.value / "io", "main").toList.map(f => file(f.getPath + "-3"))
        )
      else Nil
    },
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
      ("org.scalatestplus" %% "testng-6-7" % "3.2.1.0" % "test").withDottyCompat(scalaVersion.value)
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
