package fs2

import scala.concurrent.duration._
import cats.implicits._
import cats.effect.IO
import cats.effect.concurrent.Deferred
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
        eventually(Timeout(3 seconds)) { bgDone shouldBe true }
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
        val prg = Stream
          .eval(Deferred[IO, Unit])
          .flatMap { halt =>
            val bracketed =
              Stream.bracket(IO(new java.util.concurrent.atomic.AtomicBoolean(true)))(
                b => IO(b.set(false))
              )

            bracketed
              .flatMap { b =>
                s.get
                  .covary[IO]
                  .concurrently(
                    (Stream.eval_(IO.sleep(50.millis)) ++
                      Stream
                        .eval_(halt.complete(())))
                      .onFinalize(
                        IO.sleep(100.millis) >>
                          (if (b.get) IO.raiseError(new Err) else IO(()))
                      ))
              }
              .interruptWhen(halt.get.attempt)
          }

        an[Err] should be thrownBy runLog(prg)

    }
  }
}
