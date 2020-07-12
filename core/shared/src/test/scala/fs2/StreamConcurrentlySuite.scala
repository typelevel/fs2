package fs2

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.implicits._

class StreamConcurrentlySuite extends Fs2Suite {

  test("when background stream terminates, overall stream continues") {
    forAllAsync { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      val expected = s1.toList
      s1.delayBy[IO](25.millis)
        .concurrently(s2)
        .compile
        .toList
        .map(it => assert(it == expected))
    }
  }

  test("when background stream fails, overall stream fails") {
    forAllAsync { (s: Stream[Pure, Int]) =>
      s.delayBy[IO](25.millis)
        .concurrently(Stream.raiseError[IO](new Err))
        .compile
        .drain
        .assertThrows[Err]
    }
  }

  test("when primary stream fails, overall stream fails and background stream is terminated") {
    Stream
      .eval(Semaphore[IO](0))
      .flatMap { semaphore =>
        val bg = Stream.repeatEval(IO(1) *> IO.sleep(50.millis)).onFinalize(semaphore.release)
        val fg = Stream.raiseError[IO](new Err).delayBy(25.millis)
        fg.concurrently(bg)
          .onFinalize(semaphore.acquire)
      }
      .compile
      .drain
      .assertThrows[Err]
  }

  test("when primary stream terminates, background stream is terminated") {
    forAllAsync { (s: Stream[Pure, Int]) =>
      Stream
        .eval(Semaphore[IO](0))
        .flatMap { semaphore =>
          val bg = Stream.repeatEval(IO(1) *> IO.sleep(50.millis)).onFinalize(semaphore.release)
          val fg = s.delayBy[IO](25.millis)
          fg.concurrently(bg)
            .onFinalize(semaphore.acquire)
        }
        .compile
        .drain
    }
  }

  test("when background stream fails, primary stream fails even when hung") {
    forAllAsync { (s: Stream[Pure, Int]) =>
      Stream
        .eval(Deferred[IO, Unit])
        .flatMap { gate =>
          Stream(1)
            .delayBy[IO](25.millis)
            .append(s)
            .concurrently(Stream.raiseError[IO](new Err))
            .evalTap(_ => gate.get)
        }
        .compile
        .drain
        .assertThrows[Err]
    }
  }

  test("run finalizers of background stream and properly handle exception") {
    forAllAsync { s: Stream[Pure, Int] =>
      Ref
        .of[IO, Boolean](false)
        .flatMap { runnerRun =>
          Ref.of[IO, List[String]](Nil).flatMap { finRef =>
            Deferred[IO, Unit].flatMap { halt =>
              def runner: Stream[IO, Unit] =
                Stream
                  .bracket(runnerRun.set(true))(_ =>
                    IO.sleep(
                      100.millis
                    ) >> // assure this inner finalizer always take longer run than `outer`
                      finRef.update(_ :+ "Inner") >> // signal finalizer invoked
                      IO.raiseError[Unit](new Err) // signal a failure
                  ) >> // flag the concurrently had chance to start, as if the `s` will be empty `runner` may not be evaluated at all.
                  Stream.eval_(halt.complete(())) // immediately interrupt the outer stream

              Stream
                .bracket(IO.unit)(_ => finRef.update(_ :+ "Outer"))
                .flatMap(_ => s.covary[IO].concurrently(runner))
                .interruptWhen(halt.get.attempt)
                .compile
                .drain
                .attempt
                .flatMap { r =>
                  runnerRun.get.flatMap { runnerStarted =>
                    finRef.get.flatMap { finalizers =>
                      if (runnerStarted) IO {
                        // finalizers shall be called in correct order and
                        // exception shall be thrown
                        assert(finalizers == List("Inner", "Outer"))
                        assert(r.swap.toOption.get.isInstanceOf[Err])
                      }
                      else
                        IO {
                          // still the outer finalizer shall be run, but there is no failure in `s`
                          assert(finalizers == List("Outer"))
                          assert(r == Right(()))
                        }
                    }
                  }
                }
            }
          }
        }
    }
  }

}
