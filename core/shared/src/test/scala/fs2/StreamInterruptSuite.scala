package fs2

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.concurrent.{Deferred, Semaphore}
import cats.implicits._
import org.scalacheck.effect.PropF.forAllF

class StreamInterruptSuite extends Fs2Suite {
  val interruptRepeatCount = if (isJVM) 25 else 1

  test("1 - can interrupt a hung eval") {
    forAllF { (s: Stream[Pure, Int]) =>
      val interruptSoon = Stream.sleep_[IO](50.millis).compile.drain.attempt
      Stream
        .eval(Semaphore[IO](0))
        .flatMap { semaphore =>
          s.covary[IO].evalMap(_ => semaphore.acquire).interruptWhen(interruptSoon)
        }
        .compile
        .toList
        .map(it => assert(it == Nil))
    }
  }

  test("2 - termination successful when stream doing interruption is hung") {
    forAllF { (s: Stream[Pure, Int]) =>
      Stream
        .eval(Semaphore[IO](0))
        .flatMap { semaphore =>
          val interrupt = Stream.emit(true) ++ Stream.exec(semaphore.release)
          s.covary[IO].evalMap(_ => semaphore.acquire).interruptWhen(interrupt)
        }
        .compile
        .toList
        .map(it => assert(it == Nil))
    }
  }

  // These IO streams cannot be interrupted on JS b/c they never yield execution
  if (isJVM) {
    test("3 - constant stream") {
      val interruptSoon = Stream.sleep_[IO](20.millis).compile.drain.attempt
      Stream
        .constant(true)
        .covary[IO]
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
        .covary[IO]
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
        Stream.emit(i).covary[IO].flatMap(i => Stream.emit(i) ++ loop(i + 1))

      loop(0)
        .interruptWhen(interrupt)
        .compile
        .drain
        .replicateA(interruptRepeatCount)
    }

    test("6 - interruption of an infinitely recursive stream that never emits") {
      val interrupt =
        Stream.sleep_[IO](20.millis).compile.drain.attempt

      def loop: Stream[IO, Int] =
        Stream.eval(IO.unit) >> loop

      loop
        .interruptWhen(interrupt)
        .compile
        .drain
        .replicateA(interruptRepeatCount)
    }

    test("7 - interruption of an infinitely recursive stream that never emits and has no eval") {
      val interrupt = Stream.sleep_[IO](20.millis).compile.drain.attempt
      def loop: Stream[IO, Int] = Stream.emit(()).covary[IO] >> loop
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
        .covary[IO]
        .interruptWhen(interrupt)
        .compile
        .drain
        .replicateA(interruptRepeatCount)
    }

    test("10 - terminates when interruption stream is infinitely false") {
      forAllF { (s: Stream[Pure, Int]) =>
        s.covary[IO]
          .interruptWhen(Stream.constant(false))
          .compile
          .toList
          .map(it => assert(it == s.toList))
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
            s.covary[IO]
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
          s.covary[IO].interruptWhen(interrupt) >> Stream.exec(semaphore.acquire)
        }
        .compile
        .toList
        .map(it => assert(it == Nil))
    }
  }

  test("12a - minimal interruption of stream that never terminates in flatMap") {
    Stream(1)
      .covary[IO]
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
            .covary[IO]
            .interruptWhen(interrupt.covaryOutput[Boolean])
            .flatMap(_ => Stream.exec(semaphore.acquire))
        }
        .compile
        .toList
        .assertThrows[Err]
    }
  }

  test("14 - minimal resume on append") {
    Stream
      .eval(IO.never)
      .interruptWhen(IO.sleep(10.millis).attempt)
      .append(Stream(5))
      .compile
      .toList
      .map(it => assert(it == List(5)))
  }

  test("14a - interrupt evalMap and then resume on append") {
    forAllF { (s: Stream[Pure, Int]) =>
      val expected = s.toList
      val interrupt = IO.sleep(50.millis).attempt
      s.covary[IO]
        .interruptWhen(interrupt)
        .evalMap(_ => IO.never)
        .drain
        .append(s)
        .compile
        .toList
        .map(it => assert(it == expected))
    }
  }

  test("14b - interrupt evalMap+collect and then resume on append") {
    forAllF { (s: Stream[Pure, Int]) =>
      val expected = s.toList
      val interrupt = IO.sleep(50.millis).attempt
      s.covary[IO]
        .interruptWhen(interrupt)
        .evalMap(_ => IO.never.as(None))
        .append(s.map(Some(_)))
        .collect { case Some(v) => v }
        .compile
        .toList
        .map(it => assert(it == expected))
    }
  }

  test("15 - interruption works when flatMap is followed by collect") {
    forAllF { (s: Stream[Pure, Int]) =>
      val expected = s.toList
      val interrupt = Stream.sleep_[IO](20.millis).compile.drain.attempt
      s.covary[IO]
        .append(Stream(1))
        .interruptWhen(interrupt)
        .map(_ => None)
        .append(s.map(Some(_)))
        .flatMap {
          case None    => Stream.eval(IO.never)
          case Some(i) => Stream.emit(Some(i))
        }
        .collect { case Some(i) => i }
        .compile
        .toList
        .map(it => assert(it == expected))
    }
  }

  test("16 - if a pipe is interrupted, it will not restart evaluation") {
    def p: Pipe[IO, Int, Int] = {
      def loop(acc: Int, s: Stream[IO, Int]): Pull[IO, Int, Unit] =
        s.pull.uncons1.flatMap {
          case None           => Pull.output1[IO, Int](acc)
          case Some((hd, tl)) => Pull.output1[IO, Int](hd) >> loop(acc + hd, tl)
        }
      in => loop(0, in).stream
    }
    Stream
      .unfold(0)(i => Some((i, i + 1)))
      .flatMap(Stream.emit(_).delayBy[IO](10.millis))
      .interruptWhen(Stream.emit(true).delayBy[IO](150.millis))
      .through(p)
      .compile
      .toList
      .map(result => assert(result == (result.headOption.toList ++ result.tail.filter(_ != 0))))
  }

  test("17 - minimal resume on append with pull") {
    val interrupt = IO.sleep(100.millis).attempt
    Stream(1)
      .covary[IO]
      .unchunk
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
      .compile
      .toList
      .map(it => assert(it == List(5)))
  }

  test("18 - resume with append after evalMap interruption") {
    Stream(1)
      .covary[IO]
      .interruptWhen(IO.sleep(50.millis).attempt)
      .evalMap(_ => IO.never)
      .append(Stream(5))
      .compile
      .toList
      .map(it => assert(it == List(5)))
  }

  test("19 - interrupted eval is cancelled") {
    Deferred[IO, Unit]
      .flatMap { latch =>
        Stream
          .eval(latch.get.guarantee(latch.complete(())))
          .interruptAfter(200.millis)
          .compile
          .drain >> latch.get.as(true)
      }
      .timeout(3.seconds)
  }

  test("20 - nested-interrupt") {
    forAllF { (s: Stream[Pure, Int]) =>
      val expected = s.toList
      Stream
        .eval(Semaphore[IO](0))
        .flatMap { semaphore =>
          val interrupt = IO.sleep(50.millis).attempt
          val neverInterrupt = (IO.never: IO[Unit]).attempt
          s.covary[IO]
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
        .compile
        .toList
        .map(it => assert(it == expected))
    }
  }

  test("21 - nested-interrupt - interrupt in outer scope interrupts the inner scope") {
    Stream
      .eval(IO.async[Unit](_ => ()))
      .interruptWhen(IO.async[Either[Throwable, Unit]](_ => ()))
      .interruptWhen(IO(Right(()): Either[Throwable, Unit]))
      .compile
      .toList
      .map(it => assert(it == Nil))
  }

  test("22 - nested-interrupt - interrupt in enclosing scope recovers") {
    Stream
      .eval(IO.async[Unit](_ => ()))
      .interruptWhen(IO.async[Either[Throwable, Unit]](_ => ()))
      .append(Stream(1).delayBy[IO](10.millis))
      .interruptWhen(IO(Right(()): Either[Throwable, Unit]))
      .append(Stream(2))
      .compile
      .toList
      .map(it => assert(it == List(2)))
  }
}
