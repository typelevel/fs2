package fs2

import cats.effect._

class ErrorHandlingSpec extends Fs2Spec {

  "error handling" - {

    "ex1" in {
      var i = 0
      try {
        Pull.pure(1)
            .handleErrorWith(_ => { i += 1; Pull.pure(2) })
            .flatMap { _ => Pull.output1(i) *> Pull.raiseError(new RuntimeException("woot")) }
            .stream.toList
        fail("should not reach, exception thrown above")
      }
      catch { case e: Throwable => i shouldBe 0 }
    }

    "ex2" in {
      var i = 0
      try {
        Pull.eval(IO(1))
            .handleErrorWith(_ => { i += 1; Pull.pure(2) })
            .flatMap { _ => Pull.output1(i) *> Pull.raiseError(new RuntimeException("woot")) }
            .stream.runLog.unsafeRunSync
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
              .flatMap { _ => Pull.output1(i) *> Pull.raiseError(new RuntimeException("woot")) }
        }.stream.runLog.unsafeRunSync
        fail("should not reach, exception thrown above")
      }
      catch { case e: Throwable => i shouldBe 0 }
    }

    "ex4" in {
      var i = 0
      Pull.eval(IO(???)).handleErrorWith(_ => Pull.pure(i += 1)).flatMap { _ => Pull.output1(i) }
          .stream.runLog.unsafeRunSync
      i shouldBe 1
    }

    "ex5" in {
      var i = 0
      try {
        Stream.bracket(IO(1))(_ => Stream.eval(IO(???)), _ => IO(i += 1)).runLog.unsafeRunSync
        fail("SHOULD NOT REACH")
      }
      catch { case e: Throwable => i shouldBe 1 }
    }

    "ex6" in {
      var i = 0
      (Stream.range(0, 10).covary[IO] ++ Stream.raiseError(Err)).handleErrorWith { t => i += 1; Stream.empty }.run.unsafeRunSync
      i shouldBe 1
    }

    "ex7" in {
      try {
        (Stream.range(0, 3).covary[IO] ++ Stream.raiseError(Err)).unchunk.pull.echo.stream.run.unsafeRunSync
        fail("SHOULD NOT REACH")
      }
      catch { case e: Throwable => () }
    }

    "ex8" in {
      var i = 0
      (Stream.range(0, 3).covary[IO] ++ Stream.raiseError(Err)).unchunk.pull.echo.handleErrorWith { t => i += 1; println(i); Pull.done }.stream.run.unsafeRunSync
      i shouldBe 1
    }
  }
}
