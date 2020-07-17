package fs2

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.implicits._

class StreamSwitchMapSuite extends Fs2Suite {
  test("flatMap equivalence when switching never occurs") {
    forAllAsync { (s: Stream[Pure, Int]) =>
      val expected = s.toList
      Stream
        .eval(Semaphore[IO](1))
        .flatMap { guard =>
          s.covary[IO]
            .evalTap(_ => guard.acquire) // wait for inner to emit to prevent switching
            .onFinalize(guard.acquire) // outer terminates, wait for last inner to emit
            .switchMap(x => Stream.emit(x).onFinalize(guard.release))
        }
        .compile
        .toList
        .map(it => assert(it == expected))
    }
  }

  test("inner stream finalizer always runs before switching") {
    forAllAsync { (s: Stream[Pure, Int]) =>
      Stream
        .eval(Ref[IO].of(true))
        .flatMap { ref =>
          s.covary[IO].switchMap { _ =>
            Stream.eval(ref.get).flatMap { released =>
              if (!released) Stream.raiseError[IO](new Err)
              else
                Stream
                  .eval(ref.set(false) >> IO.sleep(20.millis))
                  .onFinalize(IO.sleep(100.millis) >> ref.set(true))
            }
          }
        }
        .compile
        .drain
    }
  }

  test("when primary stream terminates, inner stream continues") {
    forAllAsync { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      val expected = s1.last.unNoneTerminate.flatMap(s => s2 ++ Stream(s)).toList
      s1.covary[IO]
        .switchMap(s => Stream.sleep_[IO](25.millis) ++ s2 ++ Stream.emit(s))
        .compile
        .toList
        .map(it => assert(it == expected))
    }
  }

  test("when inner stream fails, overall stream fails") {
    forAllAsync { (s0: Stream[Pure, Int]) =>
      val s = Stream(0) ++ s0
      s.delayBy[IO](25.millis)
        .switchMap(_ => Stream.raiseError[IO](new Err))
        .compile
        .drain
        .assertThrows[Err]
    }
  }

  test("when primary stream fails, overall stream fails and inner stream is terminated") {
    Stream
      .eval(Semaphore[IO](0))
      .flatMap { semaphore =>
        Stream(0)
          .append(Stream.raiseError[IO](new Err).delayBy(10.millis))
          .switchMap(_ =>
            Stream.repeatEval(IO(1) *> IO.sleep(10.millis)).onFinalize(semaphore.release)
          )
          .onFinalize(semaphore.acquire)
      }
      .compile
      .drain
      .assertThrows[Err]
  }

  test("when inner stream fails, inner stream finalizer run before the primary one") {
    forAllAsync { (s0: Stream[Pure, Int]) =>
      val s = Stream(0) ++ s0
      Stream
        .eval(Deferred[IO, Boolean])
        .flatMap { verdict =>
          Stream.eval(Ref[IO].of(false)).flatMap { innerReleased =>
            s.delayBy[IO](25.millis)
              .onFinalize(innerReleased.get.flatMap(inner => verdict.complete(inner)))
              .switchMap(_ => Stream.raiseError[IO](new Err).onFinalize(innerReleased.set(true)))
              .attempt
              .drain ++
              Stream.eval(verdict.get.flatMap(if (_) IO.raiseError(new Err) else IO.unit))
          }
        }
        .compile
        .drain
        .assertThrows[Err]
    }
  }

  test("when primary stream fails, inner stream finalizer run before the primary one".ignore) {
    Stream
      .eval(Ref[IO].of(false))
      .flatMap { verdict =>
        Stream.eval(Ref[IO].of(false)).flatMap { innerReleased =>
          // TODO ideally make sure the inner stream has actually started
          (Stream(1) ++ Stream.sleep_[IO](25.millis) ++ Stream.raiseError[IO](new Err))
            .onFinalize(innerReleased.get.flatMap(inner => verdict.set(inner)))
            .switchMap(_ => Stream.repeatEval(IO(1)).onFinalize(innerReleased.set(true)))
            .attempt
            .drain ++
            Stream.eval(verdict.get.flatMap(if (_) IO.raiseError(new Err) else IO(())))
        }
      }
      .compile
      .drain
      .assertThrows[Err]
  }
}
