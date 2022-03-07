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

  val thunkToIO: Function0 ~> IO = new (Function0 ~> IO) {
    def apply[A](thunk: Function0[A]): IO[A] = IO(thunk())
  }

  val thunkToSome: Function0 ~> Some = new (Function0 ~> Some) {
    def apply[A](thunk: Function0[A]): Some[A] = Some(thunk())
  }

  val someToIO: Some ~> IO = new (Some ~> IO) {
    def apply[A](some: Some[A]): IO[A] = IO.pure(some.get)
  }

  test("1 - id") {
    forAllF { (s: Stream[Pure, Int]) =>
      s.covary[IO]
        .flatMap(i => Stream.eval(IO.pure(i)))
        .translate(cats.arrow.FunctionK.id[IO])
        .assertEmitsSameAs(s)
    }
  }

  test("2 - Appending another stream after translation") {
    forAllF { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      val translated: Stream[IO, Int] = s1
        .covary[Function0]
        .flatMap(i => Stream.eval(() => i))
        .flatMap(i => Stream.eval(() => i))
        .translate(thunkToIO)

      (translated ++ s2.covary[IO]).assertEmitsSameAs(s1 ++ s2)
    }
  }

  test("3 - ok to have multiple translates") {
    forAllF { (s: Stream[Pure, Int]) =>
      s.covary[Function0]
        .flatMap(i => Stream.eval(() => i))
        .flatMap(i => Stream.eval(() => i))
        .translate(thunkToSome)
        .flatMap(i => Stream.eval(Some(i)))
        .flatMap(i => Stream.eval(Some(i)))
        .translate(someToIO)
        .assertEmitsSameAs(s)
    }
  }

  test("4 - ok to translate after zip with effects") {
    val stream: Stream[Function0, Int] = Stream.eval(() => 1)
    stream.zip(stream).translate(thunkToIO).assertEmits(List(1 -> 1))
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
      .translate(thunkToIO)
      .assertEmits(List(1, 2))
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
      .translate(thunkToIO)
      .assertEmits(List(2, 3, 3, 4))
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
      .translate(thunkToIO)
      .assertEmits(List(1, 2))
  }

  test("stack safety") {
    Stream
      .repeatEval(IO(0))
      .translate(new (IO ~> IO) { def apply[X](x: IO[X]) = IO.defer(x) })
      .take(if (isJVM) 1000000 else 10000)
      .compile
      .drain
  }

  test("Interruption - Translate does not suppress interruptions in inner stream") {
    type Eff[A] = cats.data.EitherT[IO, String, A]
    val Eff = Async[Eff]
    val effToIO: Eff ~> IO = new (Eff ~> IO) {
      def apply[X](eff: Eff[X]): IO[X] = eff.value.flatMap {
        case Left(t)  => IO.raiseError(new RuntimeException(t))
        case Right(x) => IO.pure(x)
      }
    }

    Stream
      .eval(Eff.never)
      .merge(Stream.eval(Eff.delay(1)).delayBy(5.millis).repeat)
      .interruptAfter(10.millis)
      .translate(effToIO)
      .compile
      .drain
  }
}
