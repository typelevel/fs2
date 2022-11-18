val sbtTypelevelVersion = "0.4.17"
addSbtPlugin("org.typelevel" % "sbt-typelevel" % sbtTypelevelVersion)
addSbtPlugin("org.typelevel" % "sbt-typelevel-site" % sbtTypelevelVersion)
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.11.0")
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.4.7")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.2.0")
addSbtPlugin("com.armanbilge" % "sbt-scala-native-config-brew-github-actions" % "0.1.2")
addSbtPlugin("com.github.tkawachi" % "sbt-doctest" % "0.10.0")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.3")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.3.6")
