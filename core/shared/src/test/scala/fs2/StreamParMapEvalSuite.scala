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

import cats.effect.IO
import scala.concurrent.duration._
import cats.syntax.all._
import cats.effect.Clock
import org.scalacheck.effect.PropF.forAllF
import cats.effect.kernel.Deferred
import cats.effect.Resource
import cats.effect.Resource.ExitCase
import cats.effect.kernel.Outcome.Succeeded
import cats.effect.kernel.Outcome.Canceled
import cats.effect.kernel.Outcome.Errored
import cats.data.NonEmptyList

class StreamParMapEvalSuite extends Fs2Suite {

  val d = Deferred[IO, Unit]

  val Illegal = new IllegalArgumentException

  def err(aDef: IO[Unit]) = aDef *> IO.raiseError(Illegal)

  property("toList values") {
    def sleepLimit10AndEmit(i: Int) = IO.sleep(math.abs(i % 10).millis).as(i)

    test("paralleled .sorted equals") {
      forAllF { (s: Stream[Pure, Int]) =>
        s.covary[IO]
          .parEvalMapUnordered(Int.MaxValue)(sleepLimit10AndEmit)
          .compile
          .toList
          .map(_.sorted)
          .assertEquals(s.toList.sorted)
      }
    }

    test("no parallelism - no shuffle") {
      forAllF { (s: Stream[Pure, Int]) =>
        s.covary[IO]
          .parEvalMapUnordered(1)(sleepLimit10AndEmit)
          .compile
          .toList
          .assertEquals(s.toList)
      }
    }
  }

  test("sorts by execution time") {
    def sleepAndEmit(i: Int) = IO.sleep(i.millis).as(i)
    val s = Stream(100, 50, 0, 0).covary[IO]
    s.parEvalMapUnordered(5)(sleepAndEmit).compile.toList.assertEquals(List(0, 0, 50, 100))
  }

  test("waits for element completion") {
    def stream(d1: Deferred[IO, Unit], alloc: Deferred[IO, Unit]) =
      (Stream(d1.get) ++ Stream.exec(alloc.complete(()).void)).parEvalMapUnordered(2)(identity)

    val action =
      for {
        (d1, alloc) <- (d, d).tupled
        fib <- stream(d1, alloc).compile.drain.start
        _ <- alloc.get
        either <- IO.race(IO.sleep(500.millis), fib.join)
        _ <- d1.complete(())
        outcome <- fib.joinWithNever
      } yield either.isLeft

    action.assert
  }

  test("should launch exactly maxConcurrent") {
    def stream(defs: List[Deferred[IO, Unit]]) =
      Stream.emits(defs).covary[IO].parEvalMapUnordered(100)(_.complete(()) *> IO.never)

    val action =
      for {
        defs <- List.fill(101)(d).sequence
        fib <- stream(defs).compile.drain.start
        _ <- defs.take(100).traverse(_.get)
        either <- IO.race(IO.sleep(500.millis), defs.last.get)
      } yield either.isLeft

    action.assert
  }

  test("single error in incoming should cancel executing") {
    def stream(d: Deferred[IO, Unit]) = {
      val nev = Stream(IO.never.onCancel(d.complete(()).void))
      val s = nev ++ Stream.raiseError[IO](Illegal)
      s.parEvalMapUnordered(2)(identity)
    }

    val action =
      for {
        d1 <- d
        fib <- stream(d1).compile.drain.start
        _ <- d1.get
        outcome <- fib.join
      } yield outcome match {
        case Errored(Illegal) => true
        case _                => false
      }

    action.assert
  }

  test("single error in executing should cancel other executing") {
    def stream(d: Deferred[IO, Unit]) =
      Stream(IO.never[Unit].onCancel(d.complete(()).void), IO.raiseError[Unit](Illegal))
        .covary[IO]
        .parEvalMapUnordered(2)(identity)

    val action =
      for {
        d1 <- d
        fib <- stream(d1).compile.drain.start
        _ <- d1.get
        outcome <- fib.join
      } yield outcome match {
        case Errored(Illegal) => true
        case _                => false
      }

    action.assert
  }

  test("two errors in execution should be combined to CompositeError") {
    def stream(d1: Deferred[IO, Unit], alloc: Deferred[IO, Unit]) =
      for {
        _ <- Stream(err(d1.get), err(d1.get), IO.unit).covary[IO].parEvalMapUnordered(3)(identity)
        _ <- Stream.eval(alloc.complete(()))
      } yield {}

    val action =
      for {
        (d1, alloc) <- (d, d).tupled
        fib <- stream(d1, alloc).compile.drain.start
        _ <- alloc.get
        _ <- d1.complete(())
        outcome <- fib.join
      } yield outcome match {
        case Errored(CompositeFailure(Illegal, NonEmptyList(Illegal, List()))) => true
        case _                                                                 => false
      }

    action.assert
  }

  test("resources before and after parEvalMapUnordered should be freed") {
    def res(d: Deferred[IO, Unit]) =
      Resource
        .makeCase(IO.unit) {
          case ((), ExitCase.Canceled) => d.complete(()).void
          case _                       => IO.unit
        }

    def stream(d1: Deferred[IO, Unit], d2: Deferred[IO, Unit], alloc: Deferred[IO, Unit]) =
      for {
        _ <- Stream.resource(res(d1))
        _ <- Stream(IO.never[Unit], IO.unit).covary[IO].parEvalMapUnordered(10)(identity)
        _ <- Stream.resource(res(d2))
        _ <- Stream.eval(alloc.complete(()))
        _ <- Stream.eval(IO.never[Unit])
      } yield {}

    val action =
      for {
        (d1, d2, alloc) <- (d, d, d).tupled
        fib <- stream(d1, d2, alloc).compile.drain.start
        _ <- alloc.get
        _ <- fib.cancel
        _ <- d1.get.timeout(1.second)
        _ <- d2.get.timeout(1.second)
        outcome <- fib.join
      } yield outcome match {
        case Canceled() => true
        case _          => false
      }

    action.assert
  }
}
