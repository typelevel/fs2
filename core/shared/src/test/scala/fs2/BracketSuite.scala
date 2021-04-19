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

import cats.data.Chain
import cats.effect.{IO, Resource}
import cats.effect.kernel.Ref
import cats.syntax.all._
import org.scalacheck.effect.PropF.forAllF

class BracketSuite extends Fs2Suite {

  sealed trait BracketEvent
  case object Acquired extends BracketEvent
  case object Released extends BracketEvent

  def recordBracketEvents(events: Ref[IO, List[BracketEvent]]): Stream[IO, Unit] =
    Stream.bracket(events.update(evts => evts :+ Acquired))(_ =>
      events.update(evts => evts :+ Released)
    )

  group("single bracket") {
    def singleBracketTest[A](use: Stream[IO, A]): IO[Unit] =
      Ref[IO].of(List.empty[BracketEvent]).flatMap { events =>
        recordBracketEvents(events)
          .evalMap(_ => events.get.map(events => assertEquals(events, List(Acquired))))
          .flatMap(_ => use)
          .compile
          .drain
          .handleError { case _: Err => () } >>
          events.get.assertEquals(List(Acquired, Released))
      }

    test("normal termination")(singleBracketTest(Stream.empty))
    test("failure")(singleBracketTest(Stream.raiseError[IO](new Err)))
    test("throw from append") {
      singleBracketTest(Stream(1, 2, 3) ++ ((throw new Err): Stream[IO, Int]))
    }
  }

  group("bracket ++ bracket") {
    def appendBracketTest[A](
        use1: Stream[IO, A],
        use2: Stream[IO, A]
    ): IO[Unit] =
      Ref[IO].of(List.empty[BracketEvent]).flatMap { events =>
        recordBracketEvents(events)
          .flatMap(_ => use1)
          .append(recordBracketEvents(events).flatMap(_ => use2))
          .compile
          .drain
          .handleError { case _: Err => () } >>
          events.get.assertEquals(List(Acquired, Released, Acquired, Released))
      }

    test("normal termination")(appendBracketTest(Stream.empty, Stream.empty))
    test("failure") {
      appendBracketTest(Stream.empty, Stream.raiseError[IO](new Err))
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
          .intercept[Err] >> counter.get.assertEquals(0L)
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
        all.compile.drain >> counter.get.assertEquals(0L)
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
          assertEquals(
            buffer.toList,
            List(
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
        .drain >> counter.get.assertEquals(0L)
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
      IO.ref(List.empty[Int]).flatMap { track =>
        (0 until 10)
          .foldLeft(Stream(0).covary[IO]) { (acc, i) =>
            Stream.bracket(IO(i))(i => track.update(_ :+ i)).flatMap(_ => acc)
          }
          .compile
          .drain >> track.get.assertEquals(List(0, 1, 2, 3, 4, 5, 6, 7, 8, 9))
      }
    }

    test("scope closure") {
      IO.ref(List.empty[Int]).flatMap { track =>
        (0 until 10)
          .foldLeft(Stream.emit(1).map(_ => throw new Err): Stream[IO, Int]) { (acc, i) =>
            Stream.emit(i) ++ Stream
              .bracket(IO(i))(i => track.update(_ :+ i))
              .flatMap(_ => acc)
          }
          .attempt
          .compile
          .drain >> track.get.assertEquals(List(0, 1, 2, 3, 4, 5, 6, 7, 8, 9))
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
    Ref[IO]
      .of(List.empty[BracketEvent])
      .flatMap { events =>
        recordBracketEvents(events)
          .flatMap(_ => Stream.raiseError[IO](new Err))
          .handleErrorWith(_ => Stream.empty)
          .append(recordBracketEvents(events))
          .compile
          .drain >> events.get.assertEquals(List(Acquired, Released, Acquired, Released))
      }
  }

  def bracketCaseLikeTests(
      runOnlyEarlyTerminationTests: Boolean,
      bracketCase: IO[Unit] => ((Unit, Resource.ExitCase) => IO[Unit]) => Stream[IO, Unit]
  ) = {

    def newState = Ref[IO].of(0L -> Chain.empty[Resource.ExitCase])

    def bracketed(state: Ref[IO, (Long, Chain[Resource.ExitCase])], nondet: Boolean = false) = {
      def wait =
        IO(scala.util.Random.nextInt(50).millis).flatMap(IO.sleep).whenA(nondet)

      bracketCase {
        wait >> state.update { case (l, ecs) => (l + 1) -> ecs }
      } { (_, ec) =>
        state.update { case (l, ecs) => (l - 1) -> (ecs :+ ec) }
      }
    }

    if (!runOnlyEarlyTerminationTests) {
      test("normal termination") {
        forAllF { (s0: List[Stream[Pure, Int]]) =>
          newState.flatMap { state =>
            val s =
              s0.foldMap(s => bracketed(state).flatMap(_ => s))

            s
              .append(s.take(10))
              .take(10)
              .compile
              .drain >> state.get.map { case (count, ecs) =>
              assertEquals(count, 0L)
              assert(ecs.forall(_ == Resource.ExitCase.Succeeded))
            }
          }
        }
      }

      test("failure") {
        forAllF { (s0: List[Stream[Pure, Int]]) =>
          newState.flatMap { state =>
            val s = s0.foldMap { s =>
              bracketed(state).flatMap(_ => s ++ Stream.raiseError[IO](new Err))
            }

            s.compile.drain.attempt >> state.get.map { case (count, ecs) =>
              assertEquals(count, 0L)
              assert(ecs.forall(_.isInstanceOf[Resource.ExitCase.Errored]))
            }
          }
        }
      }
    }

    test("cancelation") {
      forAllF { (s0: Stream[Pure, Int]) =>
        newState
          .flatMap { state =>
            val s = bracketed(state, nondet = true).flatMap(_ => s0 ++ Stream.never[IO])

            s.compile.drain.background.use { _ =>
              IO.sleep(20.millis)
            } >> state.get.map { case (count, ecs) =>
              assertEquals(count, 0L)
              assert(ecs.forall(_ == Resource.ExitCase.Canceled))
            }
          }
          .timeout(20.seconds)
      }
    }

    test("interruption") {
      forAllF { (s0: Stream[Pure, Int]) =>
        newState
          .flatMap { state =>
            val s = bracketed(state, nondet = true).flatMap(_ => s0 ++ Stream.never[IO])

            s.interruptAfter(20.millis).compile.drain >> state.get.map { case (count, ecs) =>
              assertEquals(count, 0L)
              assert(ecs.forall(_ == Resource.ExitCase.Canceled))
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
