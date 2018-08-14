package fs2

import cats.effect.IO
import cats.effect.concurrent.{Ref, Semaphore}
import org.scalatest.concurrent.PatienceConfiguration.Timeout

//import cats.implicits._
import cats.implicits._
import fs2.TestUtil._

import scala.concurrent.duration._

class SwitchMapSpec extends Fs2Spec with EventuallySupport {

  "switchMap" - {

    "flatMap equivalence when switching never occurs" in forAll { s: PureStream[Int] =>
      runLog(Stream.eval(Semaphore[IO](1)).flatMap { guard =>
        s.get
          .covary[IO]
          .evalTap(_ => guard.acquire) // wait for inner to emit to prevent switching
          .onFinalize(guard.acquire) // outer terminates, wait for last inner to emit
          .switchMap(x => Stream.emit(x).onFinalize(guard.release))
      }) shouldBe runLog(s.get.covary[IO].flatMap(Stream.emit(_)))
    }

    "inner stream finalizer always runs before switching" in forAll { s: PureStream[Int] =>
      val prg = Stream.eval(Ref[IO].of(true)).flatMap { ref =>
        s.get.covary[IO].switchMap { i =>
          Stream.eval(ref.get).flatMap { released =>
            if (!released) Stream.raiseError(new Err)
            else
              Stream
                .eval(ref.set(false) *> IO.sleep(20.millis))
                .onFinalize(IO.sleep(100.millis) *> ref.set(true))
          }
        }
      }
      runLog(prg)
    }

    "when primary stream terminates, inner stream continues" in forAll {
      (s1: PureStream[Int], s2: PureStream[Int]) =>
        val prog = s1.get
          .covary[IO]
          .switchMap(s => Stream.sleep_[IO](25.millis) ++ s2.get ++ Stream.emit(s))
        val that = s1.get.covary[IO].last.unNoneTerminate.flatMap(s => s2.get ++ Stream.emit(s))
        runLog(prog) shouldBe runLog(that)
    }

    "when inner stream fails, overall stream fails" in forAll { (s: PureStream[Int], f: Failure) =>
      // filter out empty streams as switching will never occur
      if (s.get.toList.nonEmpty) {
        val prg = (Stream.sleep_[IO](25.millis) ++ s.get).switchMap(_ => f.get)
        val throws = f.get.compile.drain.attempt.unsafeRunSync.isLeft
        if (throws) {
          an[Err] should be thrownBy runLog(prg)
        } else runLog(prg)
      }
    }

    "when primary stream fails, overall stream fails and inner stream is terminated" in forAll {
      (f: Failure) =>
        var bgDone = false
        val bg = Stream.repeatEval(IO(1)).onFinalize(IO { bgDone = true })
        val prg = (Stream.emit(1) ++ Stream.sleep_[IO](10 millis) ++ f.get).switchMap(_ => bg)
        an[Err] should be thrownBy runLog(prg)
        eventually(Timeout(3 seconds)) { bgDone shouldBe true }
    }

  }
}
