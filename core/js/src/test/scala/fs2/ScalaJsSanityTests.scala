package fs2

// ScalaTest doesn't currently support use of forAll with futures, which means
// tests that run streams from within forAll cannot be expressed. Until
// ScalaTest supports this, most tests are run only on the JVM. This file contains
// unit tests for Scala.js that exercise some of the functionality that's not
// tested as a result of the ScalaTest limitation.
class ScalaJsSanityTests extends AsyncFs2Spec {

  "concurrent.join" in {
    val src: Stream[Task, Stream[Task, Int]] =
      Stream.range(1, 100).covary[Task].map { i =>
        Stream.repeatEval(Task.delay(i)).take(10)
      }
    runLogF(concurrent.join(10)(src)).map { result =>
      result.sorted shouldBe (1 until 100).toVector.flatMap(Vector.fill(10)(_)).sorted
    }
  }
}
