addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.8.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.1.0")
addSbtPlugin("com.codecommit" % "sbt-spiewak-sonatype" % "0.23.0")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.9.10")
addSbtPlugin("com.github.tkawachi" % "sbt-doctest" % "0.9.9")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.3")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.6")
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.2.24")
addSbtPlugin("org.scalablytyped.converter" % "sbt-converter" % "1.0.0-beta36")

// Needed until sjs 1.8 due to https://github.com/scala-js/scala-js-js-envs/issues/12
libraryDependencies += "org.scala-js" %% "scalajs-env-nodejs" % "1.2.1"
