// https://github.com/sbt/sbt/issues/6997
ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)

addSbtPlugin("org.typelevel" % "sbt-typelevel-ci-release" % "0.8.0")

addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.16.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.2.0")
addSbtPlugin("com.github.tkawachi" % "sbt-doctest" % "0.9.9")
addSbtPlugin("com.github.sbt" % "sbt-osgi" % "0.10.0")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.3")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.3")
