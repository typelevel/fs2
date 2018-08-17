package fs2

import cats.effect._
import TestUtil._

class ErrorHandlingSpec extends Fs2Spec {

  "error handling" - {

    "ex1" in {
      var i = 0
      try {
        Pull.pure(1)
            .covary[IO]
            .handleErrorWith(_ => { i += 1; Pull.pure(2) })
            .flatMap { _ => Pull.output1(i) >> Pull.raiseError[IO](new RuntimeException("woot")) }
            .stream.compile.toList.unsafeRunSync()
        fail("should not reach, exception thrown above")
      }
      catch { case e: Throwable => i shouldBe 0 }
    }

    "ex2" in {
      var i = 0
      try {
        Pull.eval(IO(1))
            .handleErrorWith(_ => { i += 1; Pull.pure(2) })
            .flatMap { _ => Pull.output1(i) >> Pull.raiseError[IO](new RuntimeException("woot")) }
            .stream.compile.toVector.unsafeRunSync
        fail("should not reach, exception thrown above")
      }
      catch { case e: Throwable => i shouldBe 0 }
    }

    "ex3" in {
      var i = 0
      try {
        Pull.eval(IO(1)).flatMap { x =>
          Pull.pure(x)
              .handleErrorWith(_ => { i += 1; Pull.pure(2) })
              .flatMap { _ => Pull.output1(i) >> Pull.raiseError[IO](new RuntimeException("woot")) }
        }.stream.compile.toVector.unsafeRunSync
        fail("should not reach, exception thrown above")
      }
      catch { case e: Throwable => i shouldBe 0 }
    }

    "ex4" in {
      var i = 0
      Pull.eval(IO(???)).handleErrorWith(_ => Pull.pure(i += 1)).flatMap { _ => Pull.output1(i) }
          .stream.compile.toVector.unsafeRunSync
      i shouldBe 1
    }

    "ex5" in {
      var i = 0
      try {
        Stream.bracket(IO(1))(_ => IO(i += 1)).flatMap(_ => Stream.eval(IO(???))).compile.toVector.unsafeRunSync
        fail("SHOULD NOT REACH")
      }
      catch { case e: Throwable => i shouldBe 1 }
    }

    "ex6" in {
      var i = 0
      (Stream.range(0, 10).covary[IO] ++ Stream.raiseError[IO](new Err)).handleErrorWith { t => i += 1; Stream.empty }.compile.drain.unsafeRunSync
      i shouldBe 1
    }

    "ex7" in {
      try {
        (Stream.range(0, 3).covary[IO] ++ Stream.raiseError[IO](new Err)).unchunk.pull.echo.stream.compile.drain.unsafeRunSync
        fail("SHOULD NOT REACH")
      }
      catch { case e: Throwable => () }
    }

    "ex8" in {
      var i = 0
      (Stream.range(0, 3).covary[IO] ++ Stream.raiseError[IO](new Err)).unchunk.pull.echo.handleErrorWith { t => i += 1; println(i); Pull.done }.stream.compile.drain.unsafeRunSync
      i shouldBe 1
    }
  }
}
