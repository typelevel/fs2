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

import cats.effect.IO
import cats.effect.std.Semaphore
import org.scalacheck.effect.PropF.forAllF

class StreamSpawnSuite extends Fs2Suite {

  implicit class SpawnOps[F[_], A](s: Stream[F, A]) {
    def spawning[F2[x] >: F[x]](s2: Stream[F, Any])(implicit
        F: cats.effect.Concurrent[F2]
    ): Stream[F2, A] =
      s2.spawn[F2].flatMap(_ => s)
  }

  test("when background stream terminates, overall stream continues") {
    forAllF { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      s1.delayBy[IO](25.millis).spawning[IO](s2).assertEmitsSameAs(s1)
    }
  }

  test("when background stream fails, overall stream fails") {
    forAllF { (s: Stream[Pure, Int]) =>
      s.delayBy[IO](25.millis)
        .spawning[IO](Stream.raiseError[IO](new Err))
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
        fg.spawning[IO](bg).onFinalize(semaphore.acquire)
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
          fg.spawning[IO](bg).onFinalize(semaphore.acquire)
        }
        .compile
        .drain
    }
  }

  test("when background stream fails, primary stream fails even when hung") {
    forAllF { (s: Stream[Pure, Int]) =>
      Stream
        .eval(IO.deferred[Unit])
        .flatMap { gate =>
          Stream(1)
            .delayBy[IO](25.millis)
            .append(s)
            .spawning[IO](Stream.raiseError[IO](new Err))
            .evalTap(_ => gate.get)
        }
        .intercept[Err]
        .void
    }
  }

  test("Cancelling the resulting fiber stops the background stream") {
    Stream
      .eval(Semaphore[IO](0))
      .flatMap { semaphore =>
        val bg = Stream.repeatEval(IO(1) *> IO.sleep(50.millis)).onFinalize(semaphore.release)
        bg.spawn.evalMap(fiber => IO.sleep(25.millis) >> fiber.cancel.guarantee(semaphore.acquire))
      }
      .compile
      .drain
  }

}
