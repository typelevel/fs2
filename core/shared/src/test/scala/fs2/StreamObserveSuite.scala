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
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import org.scalacheck.effect.PropF.forAllF

class StreamObserveSuite extends Fs2Suite with StreamAssertions {

  trait Observer {
    def apply[O](s: Stream[IO, O])(observation: Pipe[IO, O, INothing]): Stream[IO, O]
  }

  def observationTests(label: String, observer: Observer): Unit =
    group(label) {
      test("basic functionality") {
        forAllF { (s: Stream[Pure, Int]) =>
          IO.ref(0).flatMap { sum =>
            observer(s)(_.foreach(i => sum.update(_ + i))).compile.foldMonoid
              .flatMap(out => sum.get.assertEquals(out))
          }
        }
      }

      test("handle errors from observing sink") {
        forAllF { (s: Stream[Pure, Int]) =>
          observer(s)(_ => Stream.raiseError[IO](new Err)).attempt.compile.toList
            .map { result =>
              assertEquals(result.size, 1)
              assert(
                result.head
                  .fold(identity, r => fail(s"expected left but got Right($r)"))
                  .isInstanceOf[Err]
              )
            }
        }
      }

      test("propagate error from source") {
        forAllF { (s: Stream[Pure, Int]) =>
          observer(s.drain.covaryOutput[Int] ++ Stream.raiseError[IO](new Err))(
            _.drain
          ).attempt.compile.toList
            .map { result =>
              assertEquals(result.size, 1)
              assert(
                result.head
                  .fold(identity, r => fail(s"expected left but got Right($r)"))
                  .isInstanceOf[Err]
              )
            }
        }
      }

      group("handle finite observing sink") {
        test("1") {
          forAllF { (s: Stream[Pure, Int]) =>
            observer(s)(_ => Stream.empty).assertEmpty()
          }
        }
        test("2") {
          forAllF { (s: Stream[Pure, Int]) =>
            observer(Stream(1, 2) ++ s.covary[IO])(_.take(1).drain).assertEmpty()
          }
        }
      }

      test("handle multiple consecutive observations") {
        forAllF { (s: Stream[Pure, Int]) =>
          val sink: Pipe[IO, Int, INothing] = _.foreach(_ => IO.unit)
          observer(observer(s)(sink))(sink).assertEmitsSameAs(s)
        }
      }

      test("no hangs on failures") {
        forAllF { (s: Stream[Pure, Int]) =>
          val sink: Pipe[IO, Int, INothing] =
            in => spuriousFail(in.foreach(_ => IO.unit))
          val src: Stream[IO, Int] = spuriousFail(s.covary[IO])
          src.observe(sink).observe(sink).attempt.compile.drain
        }
      }
    }

  observationTests(
    "observe",
    new Observer {
      def apply[O](s: Stream[IO, O])(observation: Pipe[IO, O, INothing]): Stream[IO, O] =
        s.observe(observation)
    }
  )

  observationTests(
    "observeAsync",
    new Observer {
      def apply[O](s: Stream[IO, O])(observation: Pipe[IO, O, INothing]): Stream[IO, O] =
        s.observeAsync(maxQueued = 10)(observation)
    }
  )

  group("observe") {
    group("not-eager") {
      test("1 - do not pull another element before we emit the current") {
        Stream
          .eval(IO(1))
          .append(Stream.eval(IO.raiseError(new Err)))
          .observe(
            _.foreach(_ => IO.sleep(100.millis))
          ) // Have to do some work here, so that we give time for the underlying stream to try pull more
          .take(1)
          .compile
          .lastOrError
          .assertEquals(1)
      }

      test("2 - do not pull another element before downstream asks") {
        Stream
          .eval(IO(1))
          .append(Stream.eval(IO.raiseError(new Err)))
          .observe(_.drain)
          .flatMap(_ =>
            Stream.eval(IO.sleep(100.millis)) >> Stream(1, 2)
          ) // Have to do some work here, so that we give time for the underlying stream to try pull more
          .take(2)
          .assertEmits(1, 2)
      }

      test("3 - do not halt the observing sink when upstream terminates") {
        Stream
          .eval(IO(1))
          .observe(
            _.chunkN(2)
              .foreach(_ => IO.sleep(100.millis))
              // Have to do some work here, so that we give time for the underlying stream to try pull more
              .onFinalizeCase {
                case Resource.ExitCase.Canceled =>
                  IO(fail("Expected exit case of Succeeded, but got Canceled"))
                case _ => IO.unit
              }
          )
          .compile
          .drain
      }
    }
  }

  group("observeEither") {
    val s = Stream.emits(Seq(Left(1), Right("a"))).repeat.covary[IO]

    test("does not drop elements") {
      val is = Ref.of[IO, Vector[Int]](Vector.empty)
      val as = Ref.of[IO, Vector[String]](Vector.empty)
      for {
        iref <- is
        aref <- as
        iSink = (_: Stream[IO, Int]).foreach(i => iref.update(_ :+ i))
        aSink = (_: Stream[IO, String]).foreach(a => aref.update(_ :+ a))
        _ <- s.take(10).observeEither(iSink, aSink).compile.drain
        _ <- iref.get.map(_.length).assertEquals(5)
        _ <- aref.get.map(_.length).assertEquals(5)
      } yield ()
    }

    group("termination") {
      test("left") {
        s.observeEither[Int, String](_.take(0).drain, _.drain).assertEmpty()
      }

      test("right") {
        s.observeEither[Int, String](_.drain, _.take(0).drain).assertEmpty()
      }
    }
  }
}
