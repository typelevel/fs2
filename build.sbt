organization := "org.scalaz.stream"

name := "scalaz-stream"

version := "0.1-SNAPSHOT"

scalaVersion := "2.10.0"

scalacOptions ++= Seq(
  "-feature",
  "-language:implicitConversions",
  "-language:higherKinds",
  "-language:existentials",
  "-language:postfixOps"
)

// https://github.com/sbt/sbt/issues/603
conflictWarning ~= { cw =>
  cw.copy(filter = (id: ModuleID) => true, group = (id: ModuleID) => id.organization + ":" + id.name, level = Level.Error, failOnConflict = true)
}

libraryDependencies ++= Seq(
  "org.scalaz" %% "scalaz-core" % "7.1.0-SNAPSHOT",
  "org.scalaz" %% "scalaz-concurrent" % "7.1.0-SNAPSHOT",
  "org.scalaz" %% "scalaz-scalacheck-binding" % "7.1.0-SNAPSHOT" % "test",
  "org.scalacheck" %% "scalacheck" % "1.10.0" % "test"
)

resolvers ++= Seq(Resolver.sonatypeRepo("releases"), Resolver.sonatypeRepo("snapshots"))

publishTo <<= (version).apply { v =>
  val nexus = "https://oss.sonatype.org/"
  if (v.trim.endsWith("SNAPSHOT"))
    Some("Snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("Releases" at nexus + "service/local/staging/deploy/maven2")
}

credentials += {
  Seq("build.publish.user", "build.publish.password").map(k => Option(System.getProperty(k))) match {
    case Seq(Some(user), Some(pass)) =>
      Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", user, pass)
    case _ =>
      Credentials(Path.userHome / ".ivy2" / ".credentials")
  }
}

pomIncludeRepository := Function.const(false)

pomExtra := (
  <url>http://typelevel.org/scalaz</url>
  <licenses>
    <license>
      <name>MIT</name>
      <url>http://www.opensource.org/licenses/mit-license.php</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
  <scm>
    <url>https://github.com/scalaz/scalaz-stream</url>
    <connection>scm:git:git://github.com/scalaz/scalaz-stream.git</connection>
    <developerConnection>scm:git:git@github.com:scalaz/scalaz-stream.git</developerConnection>
  </scm>
  <developers>
    <developer>
      <id>pchiusano</id>
      <name>Paul Chiusano</name>
      <url>https://github.com/pchiusano</url>
    </developer>
  </developers>
)

// vim: expandtab:ts=2:sw=2
