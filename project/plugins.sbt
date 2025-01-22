val sbtTypelevelVersion = "0.7.5"
addSbtPlugin("org.typelevel" % "sbt-typelevel" % sbtTypelevelVersion)
addSbtPlugin("org.typelevel" % "sbt-typelevel-site" % sbtTypelevelVersion)
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.18.1")
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.4.17")
addSbtPlugin("com.armanbilge" % "sbt-scala-native-config-brew-github-actions" % "0.3.0")
addSbtPlugin("io.github.sbt-doctest" % "sbt-doctest" % "0.11.1")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.7")

libraryDependencySchemes += "com.lihaoyi" %% "geny" % VersionScheme.Always
