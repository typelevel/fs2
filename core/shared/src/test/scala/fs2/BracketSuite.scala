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

import cats.Applicative
import cats.data.Chain
import cats.effect.{IO, Resource, Sync, SyncIO}
import cats.effect.kernel.Ref
import cats.syntax.all._
import org.scalacheck.effect.PropF.forAllF

class BracketSuite extends Fs2Suite {

  sealed trait BracketEvent
  case object Acquired extends BracketEvent
  case object Released extends BracketEvent

  def recordBracketEvents[F[_]](events: Ref[F, Vector[BracketEvent]]): Stream[F, Unit] =
    Stream.bracket(events.update(evts => evts :+ Acquired))(_ =>
      events.update(evts => evts :+ Released)
    )

  group("single bracket") {
    def singleBracketTest[F[_]: Sync, A](use: Stream[F, A]): F[Unit] =
      for {
        events <- Ref[F].of[Vector[BracketEvent]](Vector.empty)
        _ <-
          recordBracketEvents[F](events)
            .evalMap(_ => events.get.map(events => assert(events == Vector(Acquired))))
            .flatMap(_ => use)
            .compile
            .drain
            .handleErrorWith { case _: Err => Applicative[F].pure(()) }
        _ <- events.get.map(it => assert(it == Vector(Acquired, Released)))
      } yield ()

    test("normal termination")(singleBracketTest[SyncIO, Unit](Stream.empty))
    test("failure")(singleBracketTest[SyncIO, Unit](Stream.raiseError[SyncIO](new Err)))
    test("throw from append") {
      singleBracketTest(Stream(1, 2, 3) ++ ((throw new Err): Stream[SyncIO, Int]))
    }
  }

  group("bracket ++ bracket") {
    def appendBracketTest[F[_]: Sync, A](
        use1: Stream[F, A],
        use2: Stream[F, A]
    ): F[Unit] =
      for {
        events <- Ref[F].of[Vector[BracketEvent]](Vector.empty)
        _ <-
          recordBracketEvents(events)
            .flatMap(_ => use1)
            .append(recordBracketEvents(events).flatMap(_ => use2))
            .compile
            .drain
            .handleErrorWith { case _: Err => Applicative[F].pure(()) }
        _ <- events.get.map { it =>
          assert(it == Vector(Acquired, Released, Acquired, Released))
        }
      } yield ()

    test("normal termination")(appendBracketTest[SyncIO, Unit](Stream.empty, Stream.empty))
    test("failure") {
      appendBracketTest[SyncIO, Unit](Stream.empty, Stream.raiseError[SyncIO](new Err))
    }
  }

  test("nested") {
    forAllF { (s0: List[Int], finalizerFail: Boolean) =>
      // construct a deeply nested bracket stream in which the innermost stream fails
      // and check that as we unwind the stack, all resources get released
      // Also test for case where finalizer itself throws an error
      Counter[IO].flatMap { counter =>
        val innermost: Stream[IO, Int] =
          if (finalizerFail)
            Stream
              .bracket(counter.increment)(_ => counter.decrement >> IO.raiseError(new Err))
              .drain
          else Stream.raiseError[IO](new Err)
        val nested = s0.foldRight(innermost)((i, inner) =>
          Stream
            .bracket(counter.increment)(_ => counter.decrement)
            .flatMap(_ => Stream(i) ++ inner)
        )
        nested.compile.drain
          .intercept[Err]
          .flatMap(_ => counter.get)
          .map(it => assert(it == 0L))
      }
    }
  }

  test("early termination") {
    forAllF { (s: Stream[Pure, Int], i0: Long, j0: Long, k0: Long) =>
      val i = i0 % 10
      val j = j0 % 10
      val k = k0 % 10
      Counter[IO].flatMap { counter =>
        val bracketed = Stream.bracket(counter.increment)(_ => counter.decrement) >> s
        val earlyTermination = bracketed.take(i)
        val twoLevels = bracketed.take(i).take(j)
        val twoLevels2 = bracketed.take(i).take(i)
        val threeLevels = bracketed.take(i).take(j).take(k)
        val fiveLevels = bracketed.take(i).take(j).take(k).take(j).take(i)
        val all = earlyTermination ++ twoLevels ++ twoLevels2 ++ threeLevels ++ fiveLevels
        all.compile.drain.flatMap(_ => counter.get).map(it => assert(it == 0L))
      }
    }
  }

  test("finalizer should not be called until necessary") {
    IO.defer {
      val buffer = collection.mutable.ListBuffer[String]()
      Stream
        .bracket(IO(buffer += "Acquired")) { _ =>
          buffer += "ReleaseInvoked"
          IO(buffer += "Released").void
        }
        .flatMap { _ =>
          buffer += "Used"
          Stream.emit(())
        }
        .flatMap { s =>
          buffer += "FlatMapped"
          Stream(s)
        }
        .compile
        .toList
        .map { _ =>
          assert(
            buffer.toList == List(
              "Acquired",
              "Used",
              "FlatMapped",
              "ReleaseInvoked",
              "Released"
            )
          )
        }
    }
  }

  val bracketsInSequence = if (isJVM) 1000000 else 10000
  test(s"$bracketsInSequence brackets in sequence") {
    Counter[IO].flatMap { counter =>
      Stream
        .range(0, bracketsInSequence)
        .covary[IO]
        .flatMap { _ =>
          Stream
            .bracket(counter.increment)(_ => counter.decrement)
            .flatMap(_ => Stream(1))
        }
        .compile
        .drain
        .flatMap(_ => counter.get)
        .map(it => assert(it == 0))
    }
  }

  test("evaluating a bracketed stream multiple times is safe") {
    val s = Stream
      .bracket(IO.unit)(_ => IO.unit)
      .compile
      .drain
    s.flatMap(_ => s)
  }

  group("finalizers are run in LIFO order") {
    test("explicit release") {
      IO.defer {
        var o: Vector[Int] = Vector.empty
        (0 until 10)
          .foldLeft(Stream.eval(IO(0))) { (acc, i) =>
            Stream.bracket(IO(i))(i => IO { o = o :+ i }).flatMap(_ => acc)
          }
          .compile
          .drain
          .map(_ => assert(o == Vector(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)))
      }
    }

    test("scope closure") {
      IO.defer {
        var o: Vector[Int] = Vector.empty
        (0 until 10)
          .foldLeft(Stream.emit(1).map(_ => throw new Err): Stream[IO, Int]) { (acc, i) =>
            Stream.emit(i) ++ Stream
              .bracket(IO(i))(i => IO { o = o :+ i })
              .flatMap(_ => acc)
          }
          .attempt
          .compile
          .drain
          .map(_ => assert(o == Vector(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)))
      }
    }
  }

  group("propagate error from closing the root scope") {
    val s1 = Stream.bracket(IO(1))(_ => IO.unit)
    val s2 = Stream.bracket(IO("a"))(_ => IO.raiseError(new Err))

    test("fail left")(s1.zip(s2).compile.drain.intercept[Err])
    test("fail right")(s2.zip(s1).compile.drain.intercept[Err])
  }

  test("handleErrorWith closes scopes") {
    Ref[SyncIO]
      .of[Vector[BracketEvent]](Vector.empty)
      .flatMap { events =>
        recordBracketEvents[SyncIO](events)
          .flatMap(_ => Stream.raiseError[SyncIO](new Err))
          .handleErrorWith(_ => Stream.empty)
          .append(recordBracketEvents[SyncIO](events))
          .compile
          .drain *> events.get
      }
      .map(it => assert(it == List(Acquired, Released, Acquired, Released)))
  }

  def bracketCaseLikeTests(
      runOnlyEarlyTerminationTests: Boolean,
      bracketCase: IO[Unit] => ((Unit, Resource.ExitCase) => IO[Unit]) => Stream[IO, Unit]
  ) = {
    if (!runOnlyEarlyTerminationTests) {
      test("normal termination") {
        forAllF { (s0: List[Stream[Pure, Int]]) =>
          Counter[IO].flatMap { counter =>
            var ecs: Chain[Resource.ExitCase] = Chain.empty
            val s = s0.map { s =>
              bracketCase(counter.increment) { (_, ec) =>
                counter.decrement >> IO { ecs = ecs :+ ec }
              }
                .flatMap(_ => s)
            }
            val s2 = s.foldLeft(Stream.empty: Stream[IO, Int])(_ ++ _)
            s2.append(s2.take(10)).take(10).compile.drain.flatMap(_ => counter.get).map { count =>
              assert(count == 0L)
              ecs.toList.foreach(it => assert(it == Resource.ExitCase.Succeeded))
            }
          }
        }
      }

      test("failure") {
        forAllF { (s0: List[Stream[Pure, Int]]) =>
          Counter[IO].flatMap { counter =>
            var ecs: Chain[Resource.ExitCase] = Chain.empty
            val s = s0.map { s =>
              bracketCase(counter.increment) { (_, ec) =>
                counter.decrement >> IO { ecs = ecs :+ ec }
              }
                .flatMap(_ => s ++ Stream.raiseError[IO](new Err))
            }
            val s2 = s.foldLeft(Stream.empty: Stream[IO, Int])(_ ++ _)
            s2.compile.drain.attempt.flatMap(_ => counter.get).map { count =>
              assert(count == 0L)
              ecs.toList.foreach(it => assert(it.isInstanceOf[Resource.ExitCase.Errored]))
            }
          }
        }
      }
    }

    test("cancelation") {
      forAllF { (s0: Stream[Pure, Int]) =>
        Counter[IO]
          .flatMap { counter =>
            var ecs: Chain[Resource.ExitCase] = Chain.empty
            val s =
              bracketCase(counter.increment) { (_, ec) =>
                counter.decrement >> IO { ecs = ecs :+ ec }
              }
                .flatMap(_ => s0 ++ Stream.never[IO])
            s.compile.drain.start
              .flatMap(f => IO.sleep(50.millis) >> f.cancel)
              .flatMap(_ => counter.get)
              .map { count =>
                assert(count == 0L)
                ecs.toList.foreach(it => assert(it == Resource.ExitCase.Canceled))
              }
          }
          .timeout(20.seconds)
      }
    }

    test("interruption") {
      forAllF { (s0: Stream[Pure, Int]) =>
        Counter[IO]
          .flatMap { counter =>
            var ecs: Chain[Resource.ExitCase] = Chain.empty
            val s =
              bracketCase(counter.increment) { (_, ec) =>
                counter.decrement >> IO { ecs = ecs :+ ec }
              }
                .flatMap(_ => s0 ++ Stream.never[IO])
            s.interruptAfter(50.millis).compile.drain.flatMap(_ => counter.get).map { count =>
              assert(count == 0L)
              ecs.toList.foreach(it => assert(it == Resource.ExitCase.Canceled))
            }
          }
          .timeout(20.seconds)
      }
    }
  }

  group("bracketCase") {
    bracketCaseLikeTests(false, acq => rel => Stream.bracketCase(acq)(rel))
  }

  group("bracketFull") {
    group("no polling") {
      bracketCaseLikeTests(false, acq => rel => Stream.bracketFull[IO, Unit](_ => acq)(rel))
    }
    group("polling") {
      bracketCaseLikeTests(false, acq => rel => Stream.bracketFull[IO, Unit](p => p(acq))(rel))
    }
    group("long running unmasked acquire") {
      bracketCaseLikeTests(
        true,
        acq => rel => Stream.bracketFull[IO, Unit](p => p(IO.sleep(1.hour)) *> acq)(rel)
      )
    }
  }

}
