package fs2

import scala.concurrent.duration._
import cats.implicits._
import cats.effect.IO
import cats.effect.concurrent.{Deferred, Ref}
import TestUtil._
import org.scalatest.concurrent.PatienceConfiguration.Timeout

class ConcurrentlySpec extends Fs2Spec with EventuallySupport {

  "concurrently" - {

    "when background stream terminates, overall stream continues" in forAll {
      (s1: PureStream[Int], s2: PureStream[Int]) =>
        runLog(
          (Stream.sleep_[IO](25.millis) ++ s1.get)
            .concurrently(s2.get)) shouldBe s1.get.toVector
    }

    "when background stream fails, overall stream fails" in forAll {
      (s: PureStream[Int], f: Failure) =>
        val prg = (Stream.sleep_[IO](25.millis) ++ s.get).concurrently(f.get)
        val throws = f.get.compile.drain.attempt.unsafeRunSync.isLeft
        if (throws) an[Err] should be thrownBy runLog(prg)
        else runLog(prg)
    }

    "when primary stream fails, overall stream fails and background stream is terminated" in forAll {
      (f: Failure) =>
        var bgDone = false
        val bg = Stream.repeatEval(IO(1)).onFinalize(IO { bgDone = true })
        val prg = (Stream.sleep_[IO](25.millis) ++ f.get).concurrently(bg)
        an[Err] should be thrownBy runLog(prg)
        eventually(Timeout(3.seconds)) { bgDone shouldBe true }
    }

    "when primary stream termiantes, background stream is terminated" in forAll {
      (s: PureStream[Int]) =>
        var bgDone = false
        val bg = Stream.repeatEval(IO(1)).onFinalize(IO { bgDone = true })
        val prg = (Stream.sleep_[IO](25.millis) ++ s.get).concurrently(bg)
        runLog(prg)
        bgDone shouldBe true
    }

    "when background stream fails, primary stream fails even when hung" in forAll {
      (s: PureStream[Int], f: Failure) =>
        val prg =
          Stream.eval(Deferred[IO, Unit]).flatMap { gate =>
            (Stream.sleep_[IO](25.millis) ++ Stream(1) ++ s.get)
              .concurrently(f.get)
              .evalMap(i => gate.get.as(i))
          }

        val throws = f.get.compile.drain.attempt.unsafeRunSync.isLeft
        if (throws) an[Err] should be thrownBy runLog(prg)
        else runLog(prg)
    }

    "run finalizers of background stream and properly handle exception" in forAll {
      s: PureStream[Int] =>
        val Boom = new Err

        val prg =
          Ref.of[IO, Boolean](false).flatMap { runnerRun =>
            Ref.of[IO, List[String]](Nil).flatMap { finRef =>
              Deferred[IO, Unit].flatMap { halt =>
                def bracketed: Stream[IO, Unit] =
                  Stream.bracket(IO.unit)(
                    _ => finRef.update(_ :+ "Outer")
                  )

                def runner: Stream[IO, Unit] =
                  Stream
                    .bracket(runnerRun.set(true))(_ =>
                      IO.sleep(100.millis) >> // assure this inner finalizer always take longer run than `outer`
                        finRef.update(_ :+ "Inner") >> // signal finalizer invoked
                        IO.raiseError[Unit](Boom) // signal a failure
                    ) >> // flag the concurrently had chance to start, as if the `s` will be empty `runner` may not be evaluated at all.
                    Stream.eval_(halt.complete(())) // immediately interrupt the outer stream

                val prg0 =
                  bracketed
                    .flatMap { b =>
                      s.get.covary[IO].concurrently(runner)
                    }
                    .interruptWhen(halt.get.attempt)

                prg0.compile.drain.attempt.flatMap { r =>
                  runnerRun.get.flatMap { runnerStarted =>
                    finRef.get.flatMap { finalizers =>
                      if (runnerStarted) IO {
                        // finalizers shall be called in correct order and
                        // exception shall be thrown
                        finalizers shouldBe List("Inner", "Outer")
                        r shouldBe Left(Boom)
                      } else
                        IO {
                          // still the outer finalizer shall be run, but there is no failure in `s`
                          finalizers shouldBe List("Outer")
                          r shouldBe Right(())
                        }
                    }
                  }
                }

              }
            }
          }

        prg.unsafeRunSync()

    }
  }
}
