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

import cats.effect.{IO, IOLocal, Sync}
import cats.effect.kernel.Deferred
import cats.effect.std.Semaphore
import org.scalacheck.effect.PropF.forAllF

class StreamInterruptSuite extends Fs2Suite {
  val interruptRepeatCount = if (isJVM) 25 else 1

  test("1 - can interrupt a hung eval") {
    forAllF { (s: Stream[Pure, Int]) =>
      val interruptSoon = Stream.sleep_[IO](50.millis).compile.drain.attempt
      Stream
        .eval(Semaphore[IO](0))
        .flatMap { semaphore =>
          s.evalMap(_ => semaphore.acquire).interruptWhen(interruptSoon)
        }
        .assertEmpty()
    }
  }

  test("2 - termination successful when stream doing interruption is hung") {
    forAllF { (s: Stream[Pure, Int]) =>
      Stream
        .eval(Semaphore[IO](0))
        .flatMap { semaphore =>
          val interrupt = Stream.emit(true) ++ Stream.exec(semaphore.release)
          s.evalMap(_ => semaphore.acquire).interruptWhen(interrupt)
        }
        .assertEmpty()
    }
  }

  // These IO streams cannot be interrupted on JS b/c they never yield execution
  if (isJVM) {
    test("3 - constant stream") {
      val interruptSoon = Stream.sleep_[IO](20.millis).compile.drain.attempt
      Stream
        .constant(true)
        .interruptWhen(interruptSoon)
        .compile
        .drain
        .replicateA(interruptRepeatCount)
    }

    test("4 - interruption of constant stream with a flatMap") {
      val interrupt =
        Stream.sleep_[IO](20.millis).compile.drain.attempt
      Stream
        .constant(true)
        .interruptWhen(interrupt)
        .flatMap(_ => Stream.emit(1))
        .compile
        .drain
        .replicateA(interruptRepeatCount)
    }

    test("5 - interruption of an infinitely recursive stream") {
      val interrupt =
        Stream.sleep_[IO](20.millis).compile.drain.attempt

      def loop(i: Int): Stream[IO, Int] =
        Stream.emit(i).flatMap(i => Stream.emit(i) ++ loop(i + 1))

      loop(0)
        .interruptWhen(interrupt)
        .compile
        .drain
        .replicateA(interruptRepeatCount)
    }

    test("6 - interruption of an infinitely recursive stream that never emits") {
      val interrupt =
        Stream.sleep_[IO](20.millis).compile.drain.attempt

      def loop: Stream[IO, INothing] =
        Stream.eval(IO.unit) >> loop

      loop
        .interruptWhen(interrupt)
        .compile
        .drain
        .replicateA(interruptRepeatCount)
    }

    test("7 - interruption of an infinitely recursive stream that never emits and has no eval") {
      val interrupt = Stream.sleep_[IO](20.millis).compile.drain.attempt
      def loop: Stream[IO, Int] = Stream.emit(()) >> loop
      loop
        .interruptWhen(interrupt)
        .compile
        .drain
        .replicateA(interruptRepeatCount)
    }

    test("8 - interruption of a stream that repeatedly evaluates") {
      val interrupt =
        Stream.sleep_[IO](20.millis).compile.drain.attempt
      Stream
        .repeatEval(IO.unit)
        .interruptWhen(interrupt)
        .compile
        .drain
        .replicateA(interruptRepeatCount)
    }

    test("9 - interruption of the constant drained stream") {
      val interrupt =
        Stream.sleep_[IO](1.millis).compile.drain.attempt
      Stream
        .constant(true)
        .dropWhile(!_)
        .interruptWhen(interrupt)
        .compile
        .drain
        .replicateA(interruptRepeatCount)
    }

    test("10 - terminates when interruption stream is infinitely false") {
      forAllF { (s: Stream[Pure, Int]) =>
        val allFalse = Stream.constant(false)
        s.covary[IO].interruptWhen(allFalse).assertEmitsSameAs(s)
      }
    }
  }

  test("11 - both streams hung") {
    forAllF { (s: Stream[Pure, Int]) =>
      Stream
        .eval(Semaphore[IO](0))
        .flatMap { barrier =>
          Stream.eval(Semaphore[IO](0)).flatMap { enableInterrupt =>
            val interrupt = Stream.eval(enableInterrupt.acquire) >> Stream.emit(false)
            s
              .evalMap { i =>
                // enable interruption and hang when hitting a value divisible by 7
                if (i % 7 == 0) enableInterrupt.release.flatMap(_ => barrier.acquire.as(i))
                else IO.pure(i)
              }
              .interruptWhen(interrupt)
          }
        }
        .compile
        .toList
        .map { result =>
          // as soon as we hit a value divisible by 7, we enable interruption then hang before emitting it,
          // so there should be no elements in the output that are divisible by 7
          // this also checks that interruption works fine even if one or both streams are in a hung state
          assert(result.forall(i => i % 7 != 0))
        }
    }
  }

  test("12 - interruption of stream that never terminates in flatMap") {
    forAllF { (s: Stream[Pure, Int]) =>
      val interrupt = Stream.sleep_[IO](50.millis).compile.drain.attempt
      Stream
        .eval(Semaphore[IO](0))
        .flatMap { semaphore =>
          s.interruptWhen(interrupt) >> Stream.exec(semaphore.acquire)
        }
        .assertEmpty()
    }
  }

  test("12a - minimal interruption of stream that never terminates in flatMap") {
    Stream(1)
      .interruptWhen(IO.sleep(10.millis).attempt)
      .flatMap(_ => Stream.eval(IO.never))
      .compile
      .drain
  }

  test(
    "13 - failure from interruption signal will be propagated to main stream even when flatMap stream is hung"
  ) {
    forAllF { (s: Stream[Pure, Int]) =>
      val interrupt = Stream.sleep_[IO](50.millis) ++ Stream.raiseError[IO](new Err)
      Stream
        .eval(Semaphore[IO](0))
        .flatMap { semaphore =>
          Stream(1)
            .append(s)
            .interruptWhen(interrupt.covaryOutput[Boolean])
            .flatMap(_ => Stream.exec(semaphore.acquire))
        }
        .intercept[Err]
        .void
    }
  }

  test("14 - minimal resume on append") {
    Stream
      .eval(IO.never)
      .interruptWhen(IO.sleep(10.millis).attempt)
      .append(Stream(5))
      .assertEmits(List(5))
  }

  test("14a - interrupt evalMap and then resume on append") {
    forAllF { (s: Stream[Pure, Int]) =>
      val interrupt = IO.sleep(50.millis).attempt
      s.interruptWhen(interrupt)
        .evalMap(_ => IO.never)
        .drain
        .append(s)
        .assertEmitsSameAs(s)
    }
  }

  test("14b - interrupt evalMap+collect and then resume on append") {
    forAllF { (s: Stream[Pure, Int]) =>
      val interrupt = IO.sleep(50.millis).attempt
      s.interruptWhen(interrupt)
        .evalMap(_ => IO.never.as(None))
        .append(s.map(Some(_)))
        .collect { case Some(v) => v }
        .assertEmitsSameAs(s)
    }
  }

  test("15 - interruption works when flatMap is followed by collect") {
    forAllF { (s: Stream[Pure, Int]) =>
      val interrupt = Stream.sleep_[IO](20.millis).compile.drain.attempt
      s.append(Stream(1))
        .interruptWhen(interrupt)
        .map(_ => None)
        .append(s.map(Some(_)))
        .flatMap {
          case None    => Stream.eval(IO.never)
          case Some(i) => Stream.emit(Some(i))
        }
        .collect { case Some(i) => i }
        .assertEmitsSameAs(s)
    }
  }

  test("16 - issue #1179 - if a pipe is interrupted, it will not restart evaluation") {
    def p: Pipe[IO, Int, Int] = {
      def loop(acc: Int, s: Stream[IO, Int]): Pull[IO, Int, Unit] =
        s.pull.uncons1.flatMap {
          case None           => Pull.output1[IO, Int](acc)
          case Some((hd, tl)) => Pull.output1[IO, Int](hd) >> loop(acc + hd, tl)
        }
      in => loop(0, in).stream
    }
    Stream
      .iterate(0)(_ + 1)
      .flatMap(Stream.emit(_).delayBy[IO](10.millis))
      .interruptWhen(Stream.emit(true).delayBy[IO](150.millis))
      .through(p)
      .compile
      .lastOrError
      .map(it => assertNotEquals(it, 0))
  }

  test("17 - minimal resume on append with pull") {
    val interrupt = IO.sleep(100.millis).attempt
    Stream(1)
      .covary[IO]
      .chunkLimit(1)
      .unchunks
      .interruptWhen(interrupt)
      .pull
      .uncons
      .flatMap {
        case None         => Pull.done
        case Some((_, _)) => Pull.eval(IO.never)
      }
      .stream
      .interruptScope
      .append(Stream(5))
      .assertEmits(List(5))
  }

  test("18 - resume with append after evalMap interruption") {
    Stream(1)
      .interruptWhen(IO.sleep(50.millis).attempt)
      .evalMap(_ => IO.never)
      .append(Stream(5))
      .assertEmits(List(5))
  }

  test("19 - interrupted eval is cancelled") {
    Deferred[IO, Unit]
      .flatMap { latch =>
        Stream
          .eval(latch.get.guarantee(latch.complete(()).void))
          .interruptAfter(200.millis)
          .compile
          .drain >> latch.get
      }
      .timeout(3.seconds)
  }

  test("20 - nested-interrupt") {
    forAllF { (s: Stream[Pure, Int]) =>
      Stream
        .eval(Semaphore[IO](0))
        .flatMap { semaphore =>
          val interrupt = IO.sleep(50.millis).attempt
          val neverInterrupt = (IO.never: IO[Unit]).attempt
          s
            .interruptWhen(interrupt)
            .as(None)
            .append(s.map(Option(_)))
            .interruptWhen(neverInterrupt)
            .flatMap {
              case None    => Stream.eval(semaphore.acquire.as(None))
              case Some(i) => Stream(Some(i))
            }
            .collect { case Some(i) => i }
        }
        .assertEmitsSameAs(s)
    }
  }

  test("21 - nested-interrupt - interrupt in outer scope interrupts the inner scope") {
    Stream
      .eval(IO.never[Unit])
      .interruptWhen(IO.never[Either[Throwable, Unit]])
      .interruptWhen(IO(Right(()): Either[Throwable, Unit]))
      .assertEmpty()
  }

  test("22 - nested-interrupt - interrupt in enclosing scope recovers") {
    Stream
      .eval(IO.never)
      .interruptWhen(IO.never[Either[Throwable, Unit]])
      .append(Stream(1).delayBy[IO](10.millis))
      .interruptWhen(IO(Right(()): Either[Throwable, Unit]))
      .append(Stream(2))
      .assertEmits(List(2))
  }

  def compileWithSync[F[_]: Sync, A](s: Stream[F, A]) = s.compile

  test("23 - sync compiler interruption - succeeds when interrupted never") {
    val ioNever = IO.never[Either[Throwable, Unit]]
    compileWithSync(Stream.empty[IO].interruptWhen(ioNever)).toList.assertEquals(Nil)
  }

  test("24 - sync compiler interruption - non-terminating when interrupted") {
    val s = Stream.never[IO].interruptWhen(IO.unit.attempt)
    val interrupt = IO.sleep(250.millis)
    IO.race(compileWithSync(s).drain, interrupt).map(_.isRight).assert
  }

  test("issue #2842 - no interrupt scope") {
    val s = Stream.eval(IOLocal(List.empty[Int])).flatMap { local =>
      Stream.eval(local.update(1 :: _)).flatMap { _ =>
        Stream.eval(local.update(2 :: _)).flatMap { _ =>
          Stream.eval(local.get)
        }
      }
    }
    s.interruptScope.compile.lastOrError.assertEquals(List(2, 1))
  }
}
