package fs2

import scala.concurrent.duration._

import cats.effect.{Async, IO}
import cats.effect.concurrent.Ref
import cats.implicits._
import org.scalacheck.effect.PropF.forAllF

class StreamObserveSuite extends Fs2Suite {
  trait Observer {
    def apply[F[_]: Async, O](s: Stream[F, O])(observation: Pipe[F, O, INothing]): Stream[F, O]
  }

  def observationTests(label: String, observer: Observer): Unit =
    group(label) {
      test("basic functionality") {
        forAllF { (s: Stream[Pure, Int]) =>
          Ref
            .of[IO, Int](0)
            .flatMap { sum =>
              val ints =
                observer(s.covary[IO])(_.foreach(i => sum.update(_ + i))).compile.toList
              ints.flatMap(out => sum.get.map(out -> _))
            }
            .map {
              case (out, sum) =>
                assert(out.sum == sum)
            }
        }
      }

      test("handle errors from observing sink") {
        forAllF { (s: Stream[Pure, Int]) =>
          observer(s.covary[IO])(_ => Stream.raiseError[IO](new Err)).attempt.compile.toList
            .map { result =>
              assert(result.size == 1)
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
          observer(s.drain ++ Stream.raiseError[IO](new Err))(_.drain).attempt.compile.toList
            .map { result =>
              assert(result.size == 1)
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
            observer(s.covary[IO])(_ => Stream.empty).compile.toList.map(it => assert(it == Nil))
          }
        }
        test("2") {
          forAllF { (s: Stream[Pure, Int]) =>
            observer(Stream(1, 2) ++ s.covary[IO])(_.take(1).drain).compile.toList
              .map(it => assert(it == Nil))
          }
        }
      }

      test("handle multiple consecutive observations") {
        forAllF { (s: Stream[Pure, Int]) =>
          val expected = s.toList
          val sink: Pipe[IO, Int, INothing] = _.foreach(_ => IO.unit)
          observer(observer(s.covary[IO])(sink))(sink).compile.toList
            .map(it => assert(it == expected))
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
      def apply[F[_]: Async, O](
          s: Stream[F, O]
      )(observation: Pipe[F, O, INothing]): Stream[F, O] =
        s.observe(observation)
    }
  )

  observationTests(
    "observeAsync",
    new Observer {
      def apply[F[_]: Async, O](
          s: Stream[F, O]
      )(observation: Pipe[F, O, INothing]): Stream[F, O] =
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
          ) //Have to do some work here, so that we give time for the underlying stream to try pull more
          .take(1)
          .compile
          .toList
          .map(it => assert(it == List(1)))
      }

      test("2 - do not pull another element before downstream asks") {
        Stream
          .eval(IO(1))
          .append(Stream.eval(IO.raiseError(new Err)))
          .observe(_.drain)
          .flatMap(_ =>
            Stream.eval(IO.sleep(100.millis)) >> Stream(1, 2)
          ) //Have to do some work here, so that we give time for the underlying stream to try pull more
          .take(2)
          .compile
          .toList
          .map(it => assert(it == List(1, 2)))
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
        iResult <- iref.get
        aResult <- aref.get
      } yield {
        assert(iResult.length == 5)
        assert(aResult.length == 5)
      }
    }

    group("termination") {
      test("left") {
        s.observeEither[Int, String](_.take(0).drain, _.drain)
          .compile
          .toList
          .map(r => assert(r.isEmpty))
      }

      test("right") {
        s.observeEither[Int, String](_.drain, _.take(0).drain)
          .compile
          .toList
          .map(r => assert(r.isEmpty))
      }
    }
  }
}
