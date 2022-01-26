import com.typesafe.tools.mima.core._

Global / onChangedBuildSource := ReloadOnSourceChanges
Global / stQuiet := true

ThisBuild / tlBaseVersion := "3.2"

ThisBuild / organization := "co.fs2"
ThisBuild / organizationName := "Functional Streams for Scala"
ThisBuild / startYear := Some(2013)

val NewScala = "2.13.8"

ThisBuild / crossScalaVersions := Seq("3.1.0", "2.12.15", NewScala)
ThisBuild / tlVersionIntroduced := Map("3" -> "3.0.3")

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))

ThisBuild / tlCiReleaseBranches := List("main", "series/2.5.x")

ThisBuild / githubWorkflowBuild ++= Seq(
  WorkflowStep.Run(
    List("cd scalafix", "sbt testCI"),
    name = Some("Scalafix tests"),
    cond = Some(s"matrix.scala == '$NewScala' && matrix.project == 'rootJVM'")
  )
)

ThisBuild / licenses := List(("MIT", url("http://opensource.org/licenses/MIT")))

ThisBuild / doctestTestFramework := DoctestTestFramework.ScalaCheck

ThisBuild / developers ++= List(
  "mpilquist" -> "Michael Pilquist",
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
  tlGitHubDev(username, fullName)
}

// If debugging tests, it's sometimes useful to disable parallel execution and test result buffering:
// ThisBuild / Test / parallelExecution := false
// ThisBuild / Test / testOptions += Tests.Argument(TestFrameworks.MUnit, "-b")

ThisBuild / initialCommands := s"""
    import fs2._, cats.effect._, cats.effect.implicits._, cats.effect.unsafe.implicits.global, cats.syntax.all._, scala.concurrent.duration._
  """

ThisBuild / mimaBinaryIssueFilters ++= Seq(
  // No bincompat on internal package
  ProblemFilters.exclude[Problem]("fs2.internal.*"),
  ProblemFilters.exclude[Problem]("fs2.io.internal.*"),
  // Mima reports all ScalaSignature changes as errors, despite the fact that they don't cause bincompat issues when version swapping (see https://github.com/lightbend/mima/issues/361)
  ProblemFilters.exclude[IncompatibleSignatureProblem]("*"),
  ProblemFilters.exclude[IncompatibleMethTypeProblem]("fs2.Pull#MapOutput.apply"),
  ProblemFilters.exclude[IncompatibleResultTypeProblem]("fs2.Pull#MapOutput.fun"),
  ProblemFilters.exclude[IncompatibleMethTypeProblem]("fs2.Pull#MapOutput.copy"),
  ProblemFilters.exclude[IncompatibleResultTypeProblem]("fs2.Pull#MapOutput.copy$default$2"),
  ProblemFilters.exclude[IncompatibleMethTypeProblem]("fs2.Pull#MapOutput.this"),
  ProblemFilters.exclude[AbstractClassProblem]("fs2.Pull$CloseScope"),
  ProblemFilters.exclude[DirectAbstractMethodProblem]("fs2.Pull#CloseScope.*"),
  ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.Pull#BindBind.this"),
  ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.Pull#CloseScope.*"),
  ProblemFilters.exclude[MissingClassProblem]("fs2.Pull$CloseScope$"),
  ProblemFilters.exclude[MissingClassProblem]("fs2.Pull$EvalView"),
  ProblemFilters.exclude[MissingClassProblem]("fs2.Pull$View"),
  ProblemFilters.exclude[MissingClassProblem]("fs2.Pull$View$"),
  ProblemFilters.exclude[MissingClassProblem]("fs2.Pull$BindView"),
  ProblemFilters.exclude[ReversedAbstractMethodProblem]("fs2.Pull#CloseScope.*"),
  ProblemFilters.exclude[Problem]("fs2.io.Watcher#Registration.*"),
  ProblemFilters.exclude[Problem]("fs2.io.Watcher#DefaultWatcher.*"),
  ProblemFilters.exclude[ReversedMissingMethodProblem]("fs2.io.net.tls.TLSContext.clientBuilder"),
  ProblemFilters.exclude[ReversedMissingMethodProblem]("fs2.io.net.tls.TLSContext.serverBuilder"),
  ProblemFilters.exclude[ReversedMissingMethodProblem](
    "fs2.io.net.tls.TLSContext.dtlsClientBuilder"
  ),
  ProblemFilters.exclude[ReversedMissingMethodProblem](
    "fs2.io.net.tls.TLSContext.dtlsServerBuilder"
  ),
  ProblemFilters.exclude[Problem]("fs2.io.net.tls.TLSEngine*"),
  // start #2453 cross-build fs2.io for scala.js
  // private implementation classes
  ProblemFilters.exclude[MissingClassProblem]("fs2.io.net.Socket$IntCallbackHandler"),
  ProblemFilters.exclude[MissingClassProblem]("fs2.io.net.Socket$BufferedReads"),
  ProblemFilters.exclude[MissingClassProblem]("fs2.io.net.SocketGroup$AsyncSocketGroup"),
  ProblemFilters.exclude[MissingClassProblem]("fs2.io.net.Socket$AsyncSocket"),
  ProblemFilters.exclude[MissingClassProblem](
    "fs2.io.net.DatagramSocketGroup$AsyncDatagramSocketGroup"
  ),
  ProblemFilters.exclude[MissingClassProblem]("fs2.io.net.unixsocket.UnixSockets$AsyncSocket"),
  ProblemFilters.exclude[MissingClassProblem]("fs2.io.net.unixsocket.UnixSockets$AsyncUnixSockets"),
  ProblemFilters.exclude[MissingClassProblem]("fs2.io.net.tls.TLSContext$Builder$AsyncBuilder"),
  // sealed traits
  ProblemFilters.exclude[NewMixinForwarderProblem]("fs2.io.net.Network.*"),
  ProblemFilters.exclude[NewMixinForwarderProblem]("fs2.io.net.tls.TLSContext.*"),
  ProblemFilters.exclude[InheritedNewAbstractMethodProblem]("fs2.io.net.tls.TLSContext.*"),
  ProblemFilters.exclude[InheritedNewAbstractMethodProblem]("fs2.Compiler#Target.*"),
  ProblemFilters.exclude[InheritedNewAbstractMethodProblem](
    "fs2.Compiler#TargetLowPriority#MonadErrorTarget.*"
  ),
  // end #2453
  ProblemFilters.exclude[NewMixinForwarderProblem]("fs2.io.file.Files.*"),
  ProblemFilters.exclude[ReversedMissingMethodProblem]("fs2.io.file.Files.*"),
  ProblemFilters.exclude[MissingClassProblem]("fs2.io.file.Files$AsyncFiles"),
  ProblemFilters.exclude[InheritedNewAbstractMethodProblem]("fs2.io.file.Files.F"),
  ProblemFilters.exclude[InheritedNewAbstractMethodProblem](
    "fs2.io.file.Files._runJavaCollectionResource"
  ),
  ProblemFilters.exclude[InheritedNewAbstractMethodProblem]("fs2.io.file.Files.list"),
  ProblemFilters.exclude[InheritedNewAbstractMethodProblem]("fs2.io.file.Files.watch"),
  ProblemFilters.exclude[MissingClassProblem]("fs2.Pull$MapOutput$"),
  ProblemFilters.exclude[MissingClassProblem]("fs2.Pull$MapOutput"),
  ProblemFilters.exclude[IncompatibleMethTypeProblem]("fs2.Pull.mapOutput"),
  ProblemFilters.exclude[NewMixinForwarderProblem]("fs2.compression.Compression.gzip*"),
  ProblemFilters.exclude[NewMixinForwarderProblem]("fs2.compression.Compression.gunzip*"),
  ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.compression.Compression.$init$"),
  ProblemFilters.exclude[ReversedMissingMethodProblem]("fs2.Compiler#Target.F"),
  ProblemFilters.exclude[MissingTypesProblem]("fs2.Compiler$Target$ConcurrentTarget"),
  ProblemFilters.exclude[IncompatibleResultTypeProblem]("fs2.Compiler#Target#ConcurrentTarget.F"),
  ProblemFilters.exclude[MissingClassProblem]("fs2.Compiler$TargetLowPriority$MonadCancelTarget"),
  ProblemFilters.exclude[MissingClassProblem]("fs2.Compiler$TargetLowPriority$MonadErrorTarget"),
  ProblemFilters.exclude[MissingTypesProblem]("fs2.Compiler$TargetLowPriority$SyncTarget"),
  ProblemFilters.exclude[MissingClassProblem]("fs2.Chunk$VectorChunk"),
  ProblemFilters.exclude[ReversedMissingMethodProblem](
    "fs2.compression.DeflateParams.fhCrcEnabled"
  ),
  ProblemFilters.exclude[DirectMissingMethodProblem](
    "fs2.compression.DeflateParams#DeflateParamsImpl.copy"
  ),
  ProblemFilters.exclude[DirectMissingMethodProblem](
    "fs2.compression.DeflateParams#DeflateParamsImpl.this"
  ),
  ProblemFilters.exclude[MissingTypesProblem]("fs2.compression.DeflateParams$DeflateParamsImpl$"),
  ProblemFilters.exclude[DirectMissingMethodProblem](
    "fs2.compression.DeflateParams#DeflateParamsImpl.apply"
  ),
  ProblemFilters.exclude[MissingClassProblem](
    "fs2.io.net.SocketCompanionPlatform$IntCallbackHandler"
  ),
  ProblemFilters.exclude[MissingClassProblem]("fs2.Chunk$BufferChunk"),
  ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.Chunk.makeArrayBuilder"),
  ProblemFilters.exclude[DirectMissingMethodProblem](
    "fs2.interop.reactivestreams.StreamSubscriber.fsm"
  ),
  ProblemFilters.exclude[Problem]("fs2.interop.reactivestreams.StreamSubscriber#FSM*"),
  ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.io.package.utf8Charset"),
  ProblemFilters.exclude[DirectMissingMethodProblem](
    "fs2.io.net.tls.TLSSocketPlatform.applicationProtocol"
  ),
  ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.compression.Compression.gzip$default$*"),
  ProblemFilters.exclude[DirectMissingMethodProblem](
    "fs2.compression.Compression.gunzip$default$1$"
  ),
  ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.ChunkCompanionPlatform.makeArrayBuilder")
)

lazy val root = tlCrossRootProject
  .aggregate(
    core,
    io,
    node,
    scodec,
    protocols,
    reactiveStreams,
    benchmark
  )

lazy val IntegrationTest = config("it").extend(Test)

lazy val core = crossProject(JVMPlatform, JSPlatform)
  .in(file("core"))
  .configs(IntegrationTest)
  .settings(Defaults.itSettings: _*)
  .settings(
    inConfig(IntegrationTest)(org.scalafmt.sbt.ScalafmtPlugin.scalafmtConfigSettings),
    IntegrationTest / fork := true,
    IntegrationTest / javaOptions += "-Dcats.effect.tracing.mode=none"
  )
  .settings(
    name := "fs2-core",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core" % "2.7.0",
      "org.typelevel" %%% "cats-laws" % "2.7.0" % Test,
      "org.typelevel" %%% "cats-effect" % "3.3.4",
      "org.typelevel" %%% "cats-effect-laws" % "3.3.4" % Test,
      "org.typelevel" %%% "cats-effect-testkit" % "3.3.4" % Test,
      "org.scodec" %%% "scodec-bits" % "1.1.30",
      "org.typelevel" %%% "scalacheck-effect-munit" % "1.0.3" % Test,
      "org.typelevel" %%% "munit-cats-effect-3" % "1.0.7" % Test,
      "org.typelevel" %%% "discipline-munit" % "1.0.9" % Test
    ),
    Compile / unmanagedSourceDirectories ++= {
      val major = if (scalaVersion.value.startsWith("3")) "-3" else "-2"
      List(CrossType.Pure, CrossType.Full).flatMap(
        _.sharedSrcDir(baseDirectory.value, "main").toList.map(f => file(f.getPath + major))
      )
    },
    Test / unmanagedSourceDirectories ++= {
      val major = if (scalaVersion.value.startsWith("3")) "-3" else "-2"
      List(CrossType.Pure, CrossType.Full).flatMap(
        _.sharedSrcDir(baseDirectory.value, "test").toList.map(f => file(f.getPath + major))
      )
    },
    Compile / doc / scalacOptions ++= (if (scalaVersion.value.startsWith("2.")) Seq("-nowarn")
                                       else Nil)
  )

lazy val coreJVM = core.jvm
  .settings(
    Test / fork := true,
    doctestIgnoreRegex := Some(".*NotGiven.scala")
  )

lazy val coreJS = core.js
  .disablePlugins(DoctestPlugin)
  .settings(
    Test / scalaJSStage := FastOptStage,
    jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(),
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )

lazy val node = crossProject(JSPlatform)
  .in(file("node"))
  .enablePlugins(ScalablyTypedConverterGenSourcePlugin)
  .settings(
    name := "fs2-node",
    mimaPreviousArtifacts := Set.empty,
    scalacOptions += "-nowarn",
    Compile / doc / sources := Nil,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
    Compile / npmDevDependencies += "@types/node" -> "16.11.7",
    useYarn := true,
    yarnExtraArgs += "--frozen-lockfile",
    stOutputPackage := "fs2.internal.jsdeps",
    stPrivateWithin := Some("fs2"),
    stStdlib := List("es2020"),
    stUseScalaJsDom := false,
    stIncludeDev := true
  )

lazy val io = crossProject(JVMPlatform, JSPlatform)
  .in(file("io"))
  .jsEnablePlugins(ScalaJSBundlerPlugin)
  .settings(
    name := "fs2-io",
    libraryDependencies += "com.comcast" %%% "ip4s-core" % "3.1.2",
    tlVersionIntroduced ~= { _.updated("3", "3.1.0") }
  )
  .jvmSettings(
    Test / fork := true,
    libraryDependencies ++= Seq(
      "com.github.jnr" % "jnr-unixsocket" % "0.38.17" % Optional,
      "com.google.jimfs" % "jimfs" % "1.2" % Test
    )
  )
  .jsSettings(
    tlVersionIntroduced := List("2.12", "2.13", "3").map(_ -> "3.1.0").toMap,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
    Test / npmDevDependencies += "jks-js" -> "1.0.1",
    useYarn := true,
    yarnExtraArgs += "--frozen-lockfile"
  )
  .dependsOn(core % "compile->compile;test->test")
  .jsConfigure(_.dependsOn(node.js))
  .jsSettings(
    mimaBinaryIssueFilters ++= Seq(
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("fs2.io.package.stdinUtf8"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("fs2.io.package.stdoutLines"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("fs2.io.package.stdout"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("fs2.io.package.stdin"),
      ProblemFilters
        .exclude[ReversedMissingMethodProblem]("fs2.io.net.tls.TLSSocket.applicationProtocol"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.io.*.JavaScript*Exception.this"),
      ProblemFilters.exclude[MissingClassProblem]("fs2.io.net.JavaScriptUnknownException"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "fs2.io.net.tls.TLSSocketCompanionPlatform#AsyncTLSSocket.this"
      )
    )
  )

lazy val scodec = crossProject(JVMPlatform, JSPlatform)
  .in(file("scodec"))
  .settings(
    name := "fs2-scodec",
    libraryDependencies += "org.scodec" %%% "scodec-core" % (if (
                                                               scalaVersion.value.startsWith("2.")
                                                             )
                                                               "1.11.9"
                                                             else "2.1.0"),
    tlVersionIntroduced := List("2.12", "2.13", "3").map(_ -> "3.2.0").toMap
  )
  .jsSettings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )
  .dependsOn(core % "compile->compile;test->test", io % "test")

lazy val protocols = crossProject(JVMPlatform, JSPlatform)
  .in(file("protocols"))
  .settings(
    name := "fs2-protocols",
    tlVersionIntroduced := List("2.12", "2.13", "3").map(_ -> "3.2.0").toMap
  )
  .jsSettings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )
  .dependsOn(core % "compile->compile;test->test", scodec, io)

lazy val reactiveStreams = project
  .in(file("reactive-streams"))
  .settings(
    name := "fs2-reactive-streams",
    libraryDependencies ++= Seq(
      "org.reactivestreams" % "reactive-streams" % "1.0.3",
      "org.reactivestreams" % "reactive-streams-tck" % "1.0.3" % "test",
      "org.scalatestplus" %% "testng-6-7" % "3.2.10.0" % "test"
    ),
    Test / fork := true // Otherwise SubscriberStabilitySpec fails
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
  .dependsOn(io.jvm)

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
    tlFatalWarningsInCi := false
  )
  .dependsOn(coreJVM, io.jvm, reactiveStreams, scodec.jvm)
  .enablePlugins(MdocPlugin, NoPublishPlugin)

ThisBuild / githubWorkflowBuildPostamble ++= List(
  WorkflowStep.Sbt(
    List("microsite/mdoc"),
    cond = Some(s"matrix.scala == '$NewScala' && matrix.project == 'rootJVM'")
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
