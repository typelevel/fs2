val sbtTypelevelVersion = "0.8.5"
addSbtPlugin("org.typelevel" % "sbt-typelevel" % sbtTypelevelVersion)
addSbtPlugin("org.typelevel" % "sbt-typelevel-site" % sbtTypelevelVersion)
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.21.0")
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.11")
addSbtPlugin("com.armanbilge" % "sbt-scala-native-config-brew-github-actions" % "0.4.0")
addSbtPlugin("io.github.sbt-doctest" % "sbt-doctest" % "0.12.5")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.8")

libraryDependencySchemes += "com.lihaoyi" %% "geny" % VersionScheme.Always
