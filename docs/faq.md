# Frequently Asked questions

### Why does stream evaluation sometimes hang in the REPL?

Sometimes, stream programs that call `unsafeRunSync` or other blocking operations hang in the REPL. This is a result of the Scala 2.12's lambda encoding and is tracked in [SI-9076](https://issues.scala-lang.org/browse/SI-9076). There are various workarounds:
 - Add `-Ydelambdafy:inline` to REPL arguments
 - In Ammonite, run `interp.configureCompiler(_.settings.Ydelambdafy.tryToSetColon(List("inline")))`
 - In SBT, add `scalacOptions in Console += "-Ydelambdafy:inline"`
 - Instead of calling `s.unsafeRunSync`, call `s.unsafeRunAsync(println)` or `Await.result(s.unsafeToFuture, timeout)`
