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
import cats.effect.kernel.{Deferred, Ref}
import cats.syntax.all._
import org.scalacheck.effect.PropF.forAllF

class StreamMergeSuite extends Fs2Suite with StreamAssertions {

  group("merge") {
    test("basic") {
      forAllF { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
        s1.merge(s2.covary[IO]).emitsSameUnorderedOutputsAs(s1 ++ s2)
      }
    }

    group("identity elements") {
      test("right identity: merging with empty stream on right equals left stream") {
        forAllF { (s1: Stream[Pure, Int]) =>
          s1.covary[IO].merge(Stream.empty).emitsSameOutputsAs(s1)
        }
      }
      test("left identity: merging empty stream with another stream equals the right stream") {
        forAllF { (s1: Stream[Pure, Int]) =>
          Stream.empty.merge(s1.covary[IO]).emitsSameOutputsAs(s1)
        }
      }
    }

    group("left/right failure") {
      val errorStream = Stream.raiseError[IO](new Err)
      test("1") {
        forAllF { (s1: Stream[Pure, Int]) =>
          s1.covary[IO].merge(errorStream).intercept[Err].void
        }
      }

      test("2 - never-ending flatMap, failure after emit".ignore) {
        forAllF { (s1: Stream[Pure, Int]) =>
          s1.merge(errorStream)
            .evalMap(_ => IO.never)
            .intercept[Err]
            .void
        }
      }

      /** Ignored for now because of intermittent failures.
        *
        * Reproduce with this seed:
        * "UL4hdZX4aMPzkhx51hbRhml2Kp7v4QQh9H82XieVorH="
        */
      if (isJVM)
        test("3 - constant flatMap, failure after emit".ignore) {
          forAllF { (s1: Stream[Pure, Int]) =>
            s1.merge(errorStream)
              .flatMap(_ => Stream.constant(true))
              .intercept[Err]
              .void
          }
        }
    }

    test("run finalizers of inner streams first") {
      forAllF { (s: Stream[Pure, Int], leftBiased: Boolean) =>
        // tests that finalizers of inner stream are always run before outer finalizer
        // also this will test that when the either side throws an exception in finalizer it is caught
        val err = new Err
        Ref.of[IO, List[String]](Nil).flatMap { finalizerRef =>
          Ref.of[IO, (Boolean, Boolean)]((false, false)).flatMap { sideRunRef =>
            Deferred[IO, Unit].flatMap { halt =>
              def bracketed =
                Stream.bracket(IO.unit)(_ => finalizerRef.update(_ :+ "Outer"))

              def register(side: String): IO[Unit] =
                sideRunRef.update { case (left, right) =>
                  if (side == "L") (true, right)
                  else (left, true)
                }

              def finalizer(side: String): IO[Unit] =
                // this introduces delay and failure based on bias of the test
                if (leftBiased && side == "L")
                  IO.sleep(100.millis) >> finalizerRef.update(_ :+ s"Inner $side") >> IO
                    .raiseError(err)
                else if (!leftBiased && side == "R")
                  IO.sleep(100.millis) >> finalizerRef.update(_ :+ s"Inner $side") >> IO
                    .raiseError(err)
                else IO.sleep(50.millis) >> finalizerRef.update(_ :+ s"Inner $side")

              val prg0 =
                bracketed
                  .flatMap { _ =>
                    (Stream.bracket(register("L"))(_ => finalizer("L")) >> s)
                      .merge(
                        Stream.bracket(register("R"))(_ => finalizer("R")) >>
                          Stream
                            .eval(halt.complete(())) // immediately interrupt the outer stream
                      )
                  }
                  .interruptWhen(halt.get.attempt)

              prg0.compile.drain.attempt.flatMap { r =>
                finalizerRef.get.flatMap { finalizers =>
                  sideRunRef.get.flatMap { case (left, right) =>
                    if (left && right) IO {
                      assert(
                        List("Inner L", "Inner R", "Outer").forall(finalizers.contains)
                      )
                      assertEquals(finalizers.lastOption, Some("Outer"))
                      assertEquals(r, Left(err))
                    }
                    else if (left) IO {
                      assertEquals(finalizers, List("Inner L", "Outer"))
                      if (leftBiased) assertEquals(r, Left(err))
                      else assertEquals(r, Right(()))
                    }
                    else if (right) IO {
                      assertEquals(finalizers, List("Inner R", "Outer"))
                      if (!leftBiased) assertEquals(r, Left(err))
                      else assertEquals(r, Right(()))
                    }
                    else
                      IO {
                        assertEquals(finalizers, List("Outer"))
                        assertEquals(r, Right(()))
                      }
                  }
                }
              }
            }
          }
        }
      }
    }

    group("hangs") {
      val full = if (isJVM) Stream.constant(42) else Stream.constant(42).evalTap(_ => IO.cede)
      val hang = Stream.repeatEval(IO.never[Unit])
      val hang2: Stream[IO, Nothing] = full.drain
      val hang3: Stream[IO, Nothing] =
        Stream
          .repeatEval[IO, Unit](IO.async_[Unit](cb => cb(Right(()))) >> IO.cede)
          .drain

      test("1")(full.merge(hang).take(1).compile.lastOrError.assertEquals(42))
      test("2")(full.merge(hang2).take(1).compile.lastOrError.assertEquals(42))
      test("3")(full.merge(hang3).take(1).compile.lastOrError.assertEquals(42))
      test("4")(hang.merge(full).take(1).compile.lastOrError.assertEquals(42))
      test("5")(hang2.merge(full).take(1).compile.lastOrError.assertEquals(42))
      test("6")(hang3.merge(full).take(1).compile.lastOrError.assertEquals(42))
    }
  }

  test("mergeHaltBoth") {
    forAllF { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      val s1List = s1.toList
      val s2List = s2.toList
      s1.covary[IO].map(Left(_)).mergeHaltBoth(s2.map(Right(_))).compile.toList.map { result =>
        assert(
          (result.collect { case Left(a) => a } == s1List) ||
            (result.collect { case Right(a) => a } == s2List)
        )
      }
    }
  }

  group("mergeHaltL") {
    test("mergeHaltL emits all the outputs from left stream in same order ") {
      forAllF { (leftStream: Stream[Pure, Int], rightStream: Stream[Pure, Int]) =>
        val leftTagged = leftStream.covary[IO].map(Left(_))
        val rightTagged = rightStream.covary[IO].map(Right(_))
        leftTagged
          .mergeHaltL(rightTagged)
          .collect { case Left(a) => a }
          .emitsSameOutputsAs(leftStream)
      }
    }

    test("mergeHaltL may emit a prefix of outputs from right stream") {
      forAllF { (leftStream: Stream[Pure, Int], rightStream: Stream[Pure, Char]) =>
        val leftTagged = leftStream.covary[IO].map(Left(_))
        val rightTagged = rightStream.covary[IO].map(Right(_))
        leftTagged
          .mergeHaltL(rightTagged)
          .collect { case Right(a) => a }
          .compile
          .toList
          .map { (prefix: List[Char]) =>
            assertEquals(prefix, rightStream.toList.take(prefix.length))
          }
      }
    }

  }

  test("mergeHaltR emits all outputs from right stream, in same order") {
    forAllF { (s1: Stream[Pure, Int], s2: Stream[Pure, Int]) =>
      s1.covary[IO]
        .map(Left(_))
        .mergeHaltR(s2.map(Right(_)))
        .collect { case Right(a) => a }
        .emitsSameOutputsAs(s2)
    }
  }

  test("merge not emit ahead") {
    forAllF { (v: Int) =>
      Ref
        .of[IO, Int](v)
        .flatMap { ref =>
          def sleepAndSet(value: Int): IO[Int] =
            IO.sleep(100.milliseconds) >> ref.set(value + 1) >> IO(value)

          Stream
            .repeatEval(ref.get)
            .merge(Stream.never[IO])
            .evalMap(sleepAndSet)
            .take(2)
            .emitsOutputs(List(v, v + 1))
        }
    }
  }
}
