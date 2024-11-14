val sbtTypelevelVersion = "0.7.4"
addSbtPlugin("org.typelevel" % "sbt-typelevel" % sbtTypelevelVersion)
addSbtPlugin("org.typelevel" % "sbt-typelevel-site" % sbtTypelevelVersion)
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.16.0")
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.6")
addSbtPlugin("com.armanbilge" % "sbt-scala-native-config-brew-github-actions" % "0.3.0")
addSbtPlugin("io.github.sbt-doctest" % "sbt-doctest" % "0.11.0")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.7")

libraryDependencySchemes += "com.lihaoyi" %% "geny" % VersionScheme.Always
