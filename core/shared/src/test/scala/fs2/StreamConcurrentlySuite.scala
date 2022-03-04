/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2

import scala.concurrent.duration._

import cats.data.EitherT
import cats.effect.IO
import cats.effect.kernel.{Deferred, Ref}
import cats.effect.std.Semaphore
import cats.syntax.all._
import org.scalacheck.effect.PropF.forAllF

class StreamConcurrentlySuite extends Fs2Suite with StreamAssertions {

  test("when background stream terminates, overall stream continues") {
    forAllF { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      s1.delayBy[IO](25.millis).concurrently(s2).emitsSameOutputsAs(s1)
    }
  }

  test("when background stream fails, overall stream fails") {
    forAllF { (s: Stream[Pure, Int]) =>
      s.delayBy[IO](25.millis)
        .concurrently(Stream.raiseError[IO](new Err))
        .intercept[Err]
        .void
    }
  }

  test("when primary stream fails, overall stream fails and background stream is terminated") {
    Stream
      .eval(Semaphore[IO](0))
      .flatMap { semaphore =>
        val bg = Stream.repeatEval(IO(1) *> IO.sleep(50.millis)).onFinalize(semaphore.release)
        val fg = Stream.raiseError[IO](new Err).delayBy(25.millis)
        fg.concurrently(bg).onFinalize(semaphore.acquire)
      }
      .intercept[Err]
  }

  test("when primary stream terminates, background stream is terminated") {
    forAllF { (s: Stream[Pure, Int]) =>
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
    forAllF { (s: Stream[Pure, Int]) =>
      Stream
        .eval(Deferred[IO, Unit])
        .flatMap { gate =>
          Stream(1)
            .delayBy[IO](25.millis)
            .append(s)
            .concurrently(Stream.raiseError[IO](new Err))
            .evalTap(_ => gate.get)
        }
        .intercept[Err]
        .void
    }
  }

  test("run finalizers of background stream and properly handle exception") {
    forAllF { (s: Stream[Pure, Int]) =>
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
                  Stream.exec(halt.complete(()).void) // immediately interrupt the outer stream

              Stream
                .bracket(IO.unit)(_ => finRef.update(_ :+ "Outer"))
                .flatMap(_ => s.concurrently(runner))
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
                        assertEquals(finalizers, List("Inner", "Outer"))
                        assert(r.swap.toOption.get.isInstanceOf[Err])
                      }
                      else
                        IO {
                          // still the outer finalizer shall be run, but there is no failure in `s`
                          assertEquals(finalizers, List("Outer"))
                          assertEquals(r, Right(()))
                        }
                    }
                  }
                }
            }
          }
        }
    }
  }

  test("bug 2197") {
    val iterations = 1000
    Stream
      .eval((IO.deferred[Unit], IO.ref[Int](0)).tupled)
      .flatMap { case (done, innerErrorCountRef) =>
        def handled: IO[Unit] =
          innerErrorCountRef.modify { old =>
            (old + 1, if (old < iterations) IO.unit else done.complete(()).void)
          }.flatten
        Stream(Stream(()) ++ Stream.raiseError[IO](new Err)).repeat
          .flatMap { fg =>
            fg.prefetch.handleErrorWith(_ => Stream.eval(handled)).flatMap(_ => Stream.empty)
          }
          .interruptWhen(done.get.attempt)
          .handleErrorWith(_ => Stream.empty)
          .drain ++ Stream.eval(innerErrorCountRef.get)
      }
      .compile
      .lastOrError
      .map(cnt => assert(cnt >= iterations, s"cnt: $cnt, iterations: $iterations"))
  }

  test("background stream completes with short-circuiting transformers") {
    Stream(1, 2, 3)
      .concurrently(Stream.eval(EitherT.leftT[IO, Int]("left")))
      .compile
      .lastOrError
      .value
      .assertEquals(Right(3))
  }

  test("foreground stream short-circuits") {
    Stream(1, 2, 3)
      .evalMap(n => EitherT.cond[IO](n % 2 == 0, n, "left"))
      .concurrently(Stream.eval(EitherT.rightT[IO, String](42)))
      .compile
      .lastOrError
      .value
      .assertEquals(Left("left"))
  }
}
