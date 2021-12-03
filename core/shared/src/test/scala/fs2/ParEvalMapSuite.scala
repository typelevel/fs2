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

import cats.effect.std.CountDownLatch
import cats.effect.{Deferred, IO}
import cats.syntax.all._
import org.scalacheck.effect.PropF.forAllF

import scala.concurrent.duration._

class ParEvalMapSuite extends Fs2Suite {

  private implicit class verifyOps[T](val action: IO[T]) {
    def assertNotCompletes(): IO[Unit] = IO.race(IO.sleep(1.second), action).assertEquals(Left(()))
  }

  private val u: IO[Unit] = ().pure[IO]

  private val ex: IO[Unit] = IO.raiseError(new RuntimeException)

  group("issue-2686, max distance of concurrently computing elements") {

    test("shouldn't exceed maxConcurrent in parEvalMap") {
      run(_.parEvalMap(2)(identity)).assertNotCompletes()
    }

    test("can exceed maxConcurrent in parEvalMapUnordered") {
      val action = run(_.parEvalMapUnordered(2)(identity))
      action.assertEquals(Right(()))
    }

    def run(pipe: Pipe[IO, IO[Unit], Unit]): IO[Either[Unit, Unit]] =
      Deferred[IO, Unit].flatMap { d =>
        val stream = Stream(IO.sleep(1.minute), u, d.complete(()).void).covary[IO]
        IO.race(stream.through(pipe).compile.drain, d.get)
      }
  }

  group("order") {

    test("should be preserved in parEvalMap") {
      forAllF { s: Stream[Pure, Int] =>
        val s2 = s.covary[IO].parEvalMap(Int.MaxValue)(i => IO.sleep(math.abs(i % 3).millis).as(i))
        s2.compile.toList.assertEquals(s.toList)
      }
    }

    test("may not be preserved in parEvalMapUnordered") {
      run(_.parEvalMapUnordered(Int.MaxValue)(identity)).assertEquals(List(1, 2, 3))
    }

    def run(pipe: Pipe[IO, IO[Int], Int]) =
      Stream
        .emits(List(3, 2, 1))
        .map(i => IO.sleep(50.millis * i).as(i))
        .covary[IO]
        .through(pipe)
        .compile
        .toList
  }

  group("should limit concurrency in") {

    test("parEvalMapUnordered") {
      forAllF { (l: Int, p: Int) =>
        val length = math.abs(l % 100) + 1
        val parallel = math.abs(p % 20) + 2
        val requested = math.min(length, parallel)
        val action = runWithLatch(length, requested, _.parEvalMapUnordered(parallel)(identity))
        action.assertEquals(())
      }
    }

    test("parEvalMap") {
      forAllF { (l: Int, p: Int) =>
        val length = math.abs(l % 100) + 1
        val parallel = math.abs(p % 20) + 2
        val requested = math.min(length, parallel)
        val action = runWithLatch(length, requested, _.parEvalMap(parallel)(identity))
        action.assertEquals(())
      }
    }

    test("parEvalMapUnordered can't launch more than Stream size") {
      val action = runWithLatch(100, 101, _.parEvalMapUnordered(Int.MaxValue)(identity))
      action.assertNotCompletes()
    }

    test("parEvalMap can't launch more than Stream size") {
      val action = runWithLatch(100, 101, _.parEvalMap(Int.MaxValue)(identity))
      action.assertNotCompletes()
    }

    test("parEvalMapUnordered shouldn't launch more than maxConcurrent") {
      val action = runWithLatch(100, 21, _.parEvalMapUnordered(20)(identity))
      action.assertNotCompletes()
    }

    test("parEvalMap shouldn't launch more than maxConcurrent") {
      val action = runWithLatch(100, 21, _.parEvalMap(20)(identity))
      action.assertNotCompletes()
    }

    def runWithLatch(length: Int, parallel: Int, pipe: Pipe[IO, IO[Unit], Unit]) =
      CountDownLatch[IO](parallel).flatMap { latch =>
        Stream(latch.release *> latch.await).repeatN(length).through(pipe).compile.drain
      }
  }

  group("if two errors happens only one should be reported") {

    test("parEvalMapUnordered") {
      forAllF { (i: Int) =>
        val amount = math.abs(i % 10) + 1
        CountDownLatch[IO](amount)
          .flatMap { latch =>
            val stream = Stream(latch.release *> latch.await *> ex).repeatN(amount).covary[IO]
            stream.parEvalMapUnordered(amount)(identity).compile.drain
          }
          .intercept[RuntimeException]
          .void
      }
    }

    test("parEvalMap") {
      forAllF { (i: Int) =>
        val amount = math.abs(i % 10) + 1
        CountDownLatch[IO](amount)
          .flatMap { latch =>
            val stream = Stream(latch.release *> latch.await *> ex).repeatN(amount).covary[IO]
            stream.parEvalMap(amount)(identity).compile.drain
          }
          .intercept[RuntimeException]
          .void
      }
    }
  }

  group("if error happens after stream succeeds error should be lost") {

    test("parEvalMapUnordered") {
      check(_.parEvalMapUnordered(Int.MaxValue)(identity))
    }

    test("parEvalMap") {
      check(_.parEvalMap(Int.MaxValue)(identity))
    }

    def check(pipe: Pipe[IO, IO[Unit], Unit]) =
      IO.deferred[Unit]
        .flatMap { d =>
          val simple = Stream(u, (d.get *> ex).uncancelable).covary[IO]
          val stream = simple.through(pipe).take(1).productL(Stream.eval(d.complete(()).void))
          stream.compile.toList
        }
        .assertEquals(List(()))
  }

  group("cancels running computations when error raised") {

    test("parEvalMapUnordered") {
      check(_.parEvalMap(Int.MaxValue)(identity))
    }

    test("parEvalMap") {
      check(_.parEvalMap(Int.MaxValue)(identity))
    }

    def check(pipe: Pipe[IO, IO[Unit], Unit]) =
      (CountDownLatch[IO](2), IO.deferred[Unit])
        .mapN { (latch, d) =>
          val w = latch.release *> latch.await
          val s = Stream(w *> ex, w *> IO.never.onCancel(d.complete(()).void)).covary[IO]
          pipe(s).compile.drain !> d.get
        }
        .flatten
        .assertEquals(())
  }

  group("cancels unneeded") {

    test("parEvalMapUnordered") {
      check(_.parEvalMapUnordered(2)(identity))
    }

    test("parEvalMap") {
      check(_.parEvalMap(2)(identity))
    }

    def check(pipe: Pipe[IO, IO[Unit], Unit]) =
      IO.deferred[Unit]
        .flatMap { d =>
          val cancelled = IO.never.onCancel(d.complete(()).void)
          val stream = Stream(u, cancelled).covary[IO]
          val action = stream.through(pipe).take(1).compile.drain
          action *> d.get
        }
        .assertEquals(())
  }

  group("waits for uncancellable completion") {

    test("parEvalMapUnordered") {
      check(_.parEvalMapUnordered(2)(identity))
    }

    test("parEvalMap") {
      check(_.parEvalMap(2)(identity))
    }

    def check(pipe: Pipe[IO, IO[Unit], Unit]): IO[Unit] = {
      val uncancellableMsg = "uncancellable"
      val onFinalizeMsg = "onFinalize"

      IO.ref(Vector.empty[String])
        .flatMap { ref =>
          val io = ref.update(_ :+ uncancellableMsg).void
          val onFin2 = ref.update(_ :+ onFinalizeMsg)
          CountDownLatch[IO](2).flatMap { latch =>
            val w = latch.release *> latch.await
            val stream = Stream(w *> u, (w *> io).uncancelable).covary[IO]
            val action = stream.through(pipe).take(1).compile.drain <* onFin2
            action *> ref.get
          }
        }
        .assertEquals(Vector(uncancellableMsg, onFinalizeMsg))
    }
  }

  group("issue-2726, Stream shouldn't hang after exceptions in") {

    test("parEvalMapUnordered") {
      check(_.parEvalMapUnordered(Int.MaxValue)(identity))
    }

    test("parEvalMap") {
      check(_.parEvalMap(Int.MaxValue)(identity))
    }

    def check(pipe: Pipe[IO, IO[Unit], Unit]): IO[Unit] = {
      val iterations = 100
      val stream = Stream(IO.raiseError(new RuntimeException), IO.delay(())).covary[IO]
      val action = stream.through(pipe).compile.drain.attempt.timeout(2.seconds)
      (1 to iterations).toList.as(action).sequence_
    }
  }
}
