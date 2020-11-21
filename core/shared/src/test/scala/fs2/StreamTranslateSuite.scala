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

import cats.~>
import cats.effect.{Async, IO}
import org.scalacheck.effect.PropF.forAllF

class StreamTranslateSuite extends Fs2Suite {
  test("1 - id") {
    forAllF { (s: Stream[Pure, Int]) =>
      val expected = s.toList
      s.covary[IO]
        .flatMap(i => Stream.eval(IO.pure(i)))
        .translate(cats.arrow.FunctionK.id[IO])
        .compile
        .toList
        .assertEquals(expected)
    }
  }

  test("2") {
    forAllF { (s: Stream[Pure, Int]) =>
      val expected = s.toList
      s.covary[Function0]
        .flatMap(i => Stream.eval(() => i))
        .flatMap(i => Stream.eval(() => i))
        .translate(new (Function0 ~> IO) {
          def apply[A](thunk: Function0[A]) = IO(thunk())
        })
        .compile
        .toList
        .assertEquals(expected)
    }
  }

  test("3 - ok to have multiple translates") {
    forAllF { (s: Stream[Pure, Int]) =>
      val expected = s.toList
      s.covary[Function0]
        .flatMap(i => Stream.eval(() => i))
        .flatMap(i => Stream.eval(() => i))
        .translate(new (Function0 ~> Some) {
          def apply[A](thunk: Function0[A]) = Some(thunk())
        })
        .flatMap(i => Stream.eval(Some(i)))
        .flatMap(i => Stream.eval(Some(i)))
        .translate(new (Some ~> IO) {
          def apply[A](some: Some[A]) = IO(some.get)
        })
        .compile
        .toList
        .assertEquals(expected)
    }
  }

  test("4 - ok to translate after zip with effects") {
    val stream: Stream[Function0, Int] =
      Stream.eval(() => 1)
    stream
      .zip(stream)
      .translate(new (Function0 ~> IO) {
        def apply[A](thunk: Function0[A]) = IO(thunk())
      })
      .compile
      .toList
      .assertEquals(List((1, 1)))
  }

  test("5 - ok to translate a step leg that emits multiple chunks") {
    def goStep(step: Option[Stream.StepLeg[Function0, Int]]): Pull[Function0, Int, Unit] =
      step match {
        case None       => Pull.done
        case Some(step) => Pull.output(step.head) >> step.stepLeg.flatMap(goStep)
      }
    (Stream.eval(() => 1) ++ Stream.eval(() => 2)).pull.stepLeg
      .flatMap(goStep)
      .stream
      .translate(new (Function0 ~> IO) {
        def apply[A](thunk: Function0[A]) = IO(thunk())
      })
      .compile
      .toList
      .assertEquals(List(1, 2))
  }

  test("6 - ok to translate step leg that has uncons in its structure") {
    def goStep(step: Option[Stream.StepLeg[Function0, Int]]): Pull[Function0, Int, Unit] =
      step match {
        case None       => Pull.done
        case Some(step) => Pull.output(step.head) >> step.stepLeg.flatMap(goStep)
      }
    (Stream.eval(() => 1) ++ Stream.eval(() => 2))
      .flatMap(a => Stream.emit(a))
      .flatMap(a => Stream.eval(() => a + 1) ++ Stream.eval(() => a + 2))
      .pull
      .stepLeg
      .flatMap(goStep)
      .stream
      .translate(new (Function0 ~> IO) {
        def apply[A](thunk: Function0[A]) = IO(thunk())
      })
      .compile
      .toList
      .assertEquals(List(2, 3, 3, 4))
  }

  test("7 - ok to translate step leg that is forced back in to a stream") {
    def goStep(step: Option[Stream.StepLeg[Function0, Int]]): Pull[Function0, Int, Unit] =
      step match {
        case None => Pull.done
        case Some(step) =>
          Pull.output(step.head) >> step.stream.pull.echo
      }
    (Stream.eval(() => 1) ++ Stream.eval(() => 2)).pull.stepLeg
      .flatMap(goStep)
      .stream
      .translate(new (Function0 ~> IO) {
        def apply[A](thunk: Function0[A]) = IO(thunk())
      })
      .compile
      .toList
      .assertEquals(List(1, 2))
  }

  test("stack safety") {
    Stream
      .repeatEval(IO(0))
      .translate(new (IO ~> IO) { def apply[X](x: IO[X]) = IO.defer(x) })
      .take(if (isJVM) 1000000 else 10000)
      .compile
      .drain
  }

  test("translateInterruptible") {
    type Eff[A] = cats.data.EitherT[IO, String, A]
    val Eff = Async[Eff]
    Stream
      .eval(Eff.never)
      .merge(Stream.eval(Eff.delay(1)).delayBy(5.millis).repeat)
      .interruptAfter(10.millis)
      .translateInterruptible(new (Eff ~> IO) {
        def apply[X](eff: Eff[X]) = eff.value.flatMap {
          case Left(t) => IO.raiseError(new RuntimeException(t))
          case Right(x) => IO.pure(x)
        }
      })
      .compile
      .drain
  }
}
