import com.typesafe.tools.mima.core._

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / tlBaseVersion := "3.10"

ThisBuild / organization := "co.fs2"
ThisBuild / organizationName := "Functional Streams for Scala"
ThisBuild / startYear := Some(2013)

val Scala213 = "2.13.12"

ThisBuild / scalaVersion := Scala213
ThisBuild / crossScalaVersions := Seq("2.12.18", Scala213, "3.3.1")
ThisBuild / tlVersionIntroduced := Map("3" -> "3.0.3")

ThisBuild / githubWorkflowOSes := Seq("ubuntu-latest")
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("17"))
ThisBuild / githubWorkflowBuildPreamble ++= nativeBrewInstallWorkflowSteps.value
ThisBuild / nativeBrewInstallCond := Some("matrix.project == 'rootNative'")

ThisBuild / githubWorkflowBuild ++= Seq(
  WorkflowStep.Run(
    List("cd scalafix", "sbt testCI"),
    name = Some("Scalafix tests"),
    cond = Some(s"matrix.scala == '2.13' && matrix.project == 'rootJVM'")
  )
)

ThisBuild / githubWorkflowAddedJobs +=
  WorkflowJob(
    "macos",
    "Test I/O on macOS",
    scalas = Nil,
    sbtStepPreamble = Nil,
    javas = List(githubWorkflowJavaVersions.value.head),
    oses = List("macos-latest"),
    matrixAdds = Map("project" -> List("ioJS", "ioJVM", "ioNative")),
    steps = githubWorkflowJobSetup.value.toList ++ List(
      WorkflowStep.Run(List("brew install s2n"), cond = Some("matrix.project == 'ioNative'")),
      WorkflowStep.Sbt(List("${{ matrix.project }}/test"))
    )
  )

ThisBuild / githubWorkflowPublishNeeds += "macos"

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
  ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.Pull#BindBind.*"),
  ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.Pull#CloseScope.*"),
  ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.Pull.uncons"),
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
  ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.ChunkCompanionPlatform.makeArrayBuilder"),
  ProblemFilters.exclude[ReversedMissingMethodProblem]("fs2.concurrent.Channel.trySend"),
  ProblemFilters.exclude[ReversedMissingMethodProblem]("fs2.compression.Compression.gunzip"),
  ProblemFilters.exclude[ReversedMissingMethodProblem](
    "fs2.io.net.tls.TLSContext#Builder.systemResource"
  ),
  ProblemFilters.exclude[ReversedMissingMethodProblem](
    "fs2.io.net.tls.TLSContext#Builder.insecureResource"
  ),
  ProblemFilters.exclude[DirectMissingMethodProblem]( // something funky in Scala 3.2.0 ...
    "fs2.io.net.SocketGroupCompanionPlatform#AsyncSocketGroup.this"
  ),
  ProblemFilters.exclude[DirectMissingMethodProblem](
    "fs2.io.net.tls.S2nConnection#RecvCallbackContext.readBuffer"
  ),
  ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.io.package.readBytesFromInputStream"),
  ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.io.package.readInputStreamGeneric"),
  ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.io.package.<clinit>"),
  ProblemFilters.exclude[IncompatibleResultTypeProblem]("fs2.io.net.Socket.forAsync"),
  ProblemFilters.exclude[IncompatibleMethTypeProblem](
    "fs2.io.net.SocketCompanionPlatform#AsyncSocket.this"
  ),
  ProblemFilters.exclude[IncompatibleMethTypeProblem](
    "fs2.io.net.unixsocket.UnixSocketsCompanionPlatform#AsyncSocket.this"
  ),
  // Private stuff (#3130).
  ProblemFilters.exclude[MissingClassProblem](
    "fs2.interop.flow.StreamSubscription$Request"
  ),
  ProblemFilters.exclude[MissingClassProblem](
    "fs2.interop.flow.StreamSubscription$Request$"
  ),
  ProblemFilters.exclude[MissingClassProblem](
    "fs2.interop.flow.StreamSubscription$Request$Finite"
  ),
  ProblemFilters.exclude[MissingClassProblem](
    "fs2.interop.flow.StreamSubscription$Request$Finite$"
  ),
  ProblemFilters.exclude[MissingClassProblem](
    "fs2.interop.flow.StreamSubscription$Request$Infinite$"
  ),
  ProblemFilters.exclude[MissingClassProblem](
    "fs2.interop.flow.StreamSubscriber$FSM"
  ),
  ProblemFilters.exclude[DirectMissingMethodProblem](
    "fs2.io.net.DatagramSocketGroupCompanionPlatform#AsyncDatagramSocketGroup.this"
  ),
  ProblemFilters.exclude[DirectMissingMethodProblem](
    "fs2.io.file.Watcher#DefaultWatcher.this"
  ),
  // Private internal method: #3274
  ProblemFilters.exclude[DirectMissingMethodProblem](
    "fs2.Chunk.platformIterable"
  ),
  ProblemFilters.exclude[ReversedMissingMethodProblem](
    "fs2.concurrent.Channel.closeWithElement"
  ),
  ProblemFilters.exclude[InheritedNewAbstractMethodProblem](
    "fs2.io.file.Files.openSeekableByteChannel"
  ),
  // package-private method: #3318
  ProblemFilters.exclude[IncompatibleMethTypeProblem](
    "fs2.io.package.readInputStreamGeneric"
  ),
  // sealed trait: #3349
  ProblemFilters.exclude[ReversedMissingMethodProblem](
    "fs2.io.net.tls.TLSParameters.withClientAuthType"
  )
)

lazy val root = tlCrossRootProject
  .aggregate(
    core,
    io,
    scodec,
    protocols,
    reactiveStreams,
    integration,
    unidocs,
    benchmark
  )

lazy val commonNativeSettings = Seq[Setting[_]](
  tlVersionIntroduced := List("2.12", "2.13", "3").map(_ -> "3.2.15").toMap,
  Test / nativeBrewFormulas += "openssl"
)

lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("core"))
  .settings(
    name := "fs2-core",
    libraryDependencies ++= Seq(
      "org.scodec" %%% "scodec-bits" % "1.1.38",
      "org.typelevel" %%% "cats-core" % "2.10.0",
      "org.typelevel" %%% "cats-effect" % "3.6-e9aeb8c",
      "org.typelevel" %%% "cats-effect-laws" % "3.6-e9aeb8c" % Test,
      "org.typelevel" %%% "cats-effect-testkit" % "3.6-e9aeb8c" % Test,
      "org.typelevel" %%% "cats-laws" % "2.10.0" % Test,
      "org.typelevel" %%% "discipline-munit" % "2.0.0-M3" % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.0.0-M4" % Test,
      "org.typelevel" %%% "scalacheck-effect-munit" % "2.0.0-M2" % Test
    ),
    tlJdkRelease := None,
    Compile / doc / scalacOptions ++= (if (scalaVersion.value.startsWith("2.")) Seq("-nowarn")
                                       else Nil)
  )

lazy val coreJVM = core.jvm
  .settings(
    Test / fork := true,
    libraryDependencies ++= Seq(
      "org.reactivestreams" % "reactive-streams-tck-flow" % "1.0.4" % Test,
      "org.scalatestplus" %% "testng-7-5" % "3.2.14.0" % Test
    ),
    doctestIgnoreRegex := Some(".*NotGiven.scala")
  )

lazy val coreJS = core.js
  .disablePlugins(DoctestPlugin)
  .settings(
    Test / scalaJSStage := FastOptStage,
    jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(),
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )

lazy val coreNative = core.native
  .enablePlugins(ScalaNativeBrewedConfigPlugin)
  .disablePlugins(DoctestPlugin)
  .settings(commonNativeSettings)

lazy val integration = project
  .in(file("integration"))
  .settings(
    fork := true,
    javaOptions += "-Dcats.effect.tracing.mode=none",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "munit-cats-effect" % "2.0.0-M4" % Test
    )
  )
  .enablePlugins(NoPublishPlugin)
  .disablePlugins(DoctestPlugin)
  .dependsOn(coreJVM)

lazy val io = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("io"))
  .settings(
    name := "fs2-io",
    tlVersionIntroduced ~= { _.updated("3", "3.1.0") },
    libraryDependencies += "com.comcast" %%% "ip4s-core" % "3.4.0",
    tlJdkRelease := None
  )
  .jvmSettings(
    Test / fork := true,
    libraryDependencies ++= Seq(
      "com.github.jnr" % "jnr-unixsocket" % "0.38.21" % Optional,
      "com.google.jimfs" % "jimfs" % "1.3.0" % Test
    )
  )
  .jsSettings(
    tlVersionIntroduced := List("2.12", "2.13", "3").map(_ -> "3.1.0").toMap,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )
  .nativeEnablePlugins(ScalaNativeBrewedConfigPlugin)
  .nativeSettings(commonNativeSettings)
  .nativeSettings(
    Test / nativeBrewFormulas += "s2n",
    Test / envVars ++= Map("S2N_DONT_MLOCK" -> "1")
  )
  .dependsOn(core % "compile->compile;test->test")
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
      ),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("fs2.io.file.FileHandle.make"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("fs2.io.net.DatagramSocket.forAsync"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem](
        "fs2.io.net.DatagramSocketCompanionPlatform#AsyncDatagramSocket.this"
      ),
      ProblemFilters
        .exclude[ReversedMissingMethodProblem]("fs2.io.net.DatagramSocketOption#Key.set"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("fs2.io.net.Socket.forAsync"),
      ProblemFilters.exclude[IncompatibleMethTypeProblem]("fs2.io.net.SocketOption.encoding"),
      ProblemFilters
        .exclude[ReversedMissingMethodProblem]("fs2.io.net.SocketOptionCompanionPlatform#Key.set"),
      ProblemFilters
        .exclude[ReversedMissingMethodProblem]("fs2.io.net.tls.SecureContext#SecureVersion.toJS"),
      ProblemFilters.exclude[MissingClassProblem]("fs2.io.net.tls.SecureContext$ops"),
      ProblemFilters.exclude[Problem]("fs2.io.net.tls.SecureContext.ops"),
      ProblemFilters.exclude[MissingClassProblem]("fs2.io.net.tls.SecureContext$ops$"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem](
        "fs2.io.net.tls.TLSParameters#DefaultTLSParameters.toTLSSocketOptions"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "fs2.io.net.tls.TLSParameters#DefaultTLSParameters.toConnectionOptions"
      ),
      ProblemFilters.exclude[IncompatibleResultTypeProblem](
        "fs2.io.net.tls.SecureContext#SecureVersion#TLSv1.1.toJS"
      ),
      ProblemFilters.exclude[IncompatibleResultTypeProblem](
        "fs2.io.net.tls.SecureContext#SecureVersion#TLSv1.2.toJS"
      ),
      ProblemFilters.exclude[IncompatibleResultTypeProblem](
        "fs2.io.net.tls.SecureContext#SecureVersion#TLSv1.3.toJS"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem]("fs2.io.net.tls.TLSSocket.forAsync"),
      ProblemFilters.exclude[IncompatibleResultTypeProblem](
        "fs2.io.net.tls.TLSParameters#DefaultTLSParameters.toTLSConnectOptions"
      )
    )
  )

lazy val scodec = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("scodec"))
  .settings(
    name := "fs2-scodec",
    libraryDependencies += "org.scodec" %%% "scodec-core" % (if (
                                                               scalaVersion.value.startsWith("2.")
                                                             )
                                                               "1.11.10"
                                                             else "2.2.1"),
    tlVersionIntroduced := List("2.12", "2.13", "3").map(_ -> "3.2.0").toMap,
    tlJdkRelease := Some(8)
  )
  .jsSettings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )
  .nativeEnablePlugins(ScalaNativeBrewedConfigPlugin)
  .nativeSettings(commonNativeSettings)
  .dependsOn(core % "compile->compile;test->test", io % "test")

lazy val protocols = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .in(file("protocols"))
  .settings(
    name := "fs2-protocols",
    tlVersionIntroduced := List("2.12", "2.13", "3").map(_ -> "3.2.0").toMap,
    tlJdkRelease := Some(8)
  )
  .jsSettings(
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )
  .nativeEnablePlugins(ScalaNativeBrewedConfigPlugin)
  .nativeSettings(commonNativeSettings)
  .dependsOn(core % "compile->compile;test->test", scodec, io)

lazy val reactiveStreams = project
  .in(file("reactive-streams"))
  .settings(
    name := "fs2-reactive-streams",
    libraryDependencies ++= Seq(
      "org.reactivestreams" % "reactive-streams" % "1.0.4",
      "org.reactivestreams" % "reactive-streams-tck" % "1.0.4" % "test",
      "org.scalatestplus" %% "testng-7-5" % "3.2.17.0" % "test"
    ),
    tlJdkRelease := Some(8),
    Test / fork := true // Otherwise SubscriberStabilitySpec fails
  )
  .dependsOn(coreJVM % "compile->compile;test->test")

lazy val unidocs = project
  .in(file("unidocs"))
  .enablePlugins(TypelevelUnidocPlugin)
  .settings(
    name := "fs2-docs",
    tlJdkRelease := None,
    tlFatalWarnings := false,
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(
      core.jvm,
      io.jvm,
      scodec.jvm,
      protocols.jvm,
      reactiveStreams
    )
  )

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
    laikaSite := {
      sbt.IO.copyDirectory(mdocOut.value, (laikaSite / target).value)
      Set.empty
    },
    tlJdkRelease := None,
    tlFatalWarnings := false,
    tlSiteApiPackage := Some("fs2")
  )
  .dependsOn(coreJVM, io.jvm, reactiveStreams, scodec.jvm)
  .enablePlugins(TypelevelSitePlugin)
