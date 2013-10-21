organization := "org.scalaz.stream"

name := "scalaz-stream"

version := "0.2a"

scalaVersion := "2.10.3"

scalacOptions ++= Seq(
  "-feature",
  "-language:implicitConversions",
  "-language:higherKinds",
  "-language:existentials",
  "-language:postfixOps"
)

resolvers ++= Seq(Resolver.sonatypeRepo("releases"), Resolver.sonatypeRepo("snapshots"))

libraryDependencies ++= Seq(
  "org.scalaz" %% "scalaz-core" % "7.1.0-M2",
  "org.scalaz" %% "scalaz-concurrent" % "7.1.0-M2",
  "org.scalaz" %% "scalaz-scalacheck-binding" % "7.1.0-M2" % "test",
  "org.scalacheck" %% "scalacheck" % "1.10.0" % "test"
)

seq(bintraySettings:_*)

publishMavenStyle := true

licenses += ("MIT", url("http://opensource.org/licenses/MIT"))

bintray.Keys.packageLabels in bintray.Keys.bintray := 
  Seq("stream processing", "functional I/O", "iteratees", "functional programming", "scala")
