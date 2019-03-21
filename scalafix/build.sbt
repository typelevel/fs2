lazy val V = _root_.scalafix.sbt.BuildInfo
inThisBuild(
  List(
    organization := "co.fs2",
    homepage := Some(url("https://github.com/functional-streams-for-scala/fs2")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer(
        "amarrella",
        "Alessandro Marrella",
        "hello@alessandromarrella.com",
        url("https://alessandromarrella.com")
      )
    ),
    scalaVersion := V.scala212,
    addCompilerPlugin(scalafixSemanticdb),
    scalacOptions ++= List(
      "-Yrangepos"
    )
  )
)

skip in publish := true

lazy val rules = project.settings(
  moduleName := "scalafix",
  libraryDependencies += "ch.epfl.scala" %% "scalafix-core" % V.scalafixVersion
)

lazy val input = project.settings(
  skip in publish := true,
  libraryDependencies ++= Seq(
    "co.fs2" %% "fs2-core" % "0.10.6",
    "com.typesafe.akka" %% "akka-stream" % "2.5.21"
  )
)

lazy val output = project.settings(
  skip in publish := true,
  libraryDependencies ++= Seq(
    "co.fs2" %% "fs2-core" % "1.0.0"
  )
)

lazy val tests = project
  .settings(
    skip in publish := true,
    libraryDependencies += "ch.epfl.scala" % "scalafix-testkit" % V.scalafixVersion % Test cross CrossVersion.full,
    compile.in(Compile) := 
      compile.in(Compile).dependsOn(compile.in(input, Compile)).value,
    scalafixTestkitOutputSourceDirectories :=
      sourceDirectories.in(output, Compile).value,
    scalafixTestkitInputSourceDirectories :=
      sourceDirectories.in(input, Compile).value,
    scalafixTestkitInputClasspath :=
      fullClasspath.in(input, Compile).value,
  )
  .dependsOn(rules)
  .enablePlugins(ScalafixTestkitPlugin)
