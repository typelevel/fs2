package fs2

import cats.effect.IO
import cats.effect.concurrent.{Ref, Semaphore}

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

    "inner stream failure" in {
      forAll { (s1: PureStream[Int], f: Failure) =>
      if  (s1.get.toList.length > 0) {
          
      println("Si="  + s1 + " f="+ f)
        an[Err] should be thrownBy {
          runLog((Stream.sleep_[IO](25 millis) ++ s1.get).switchMap(_ => f.get))
          println("----")
        }
        }
      }
    }

    "constant flatMap, failure after emit" in {
      forAll { (s1: PureStream[Int], f: Failure) =>


        if  (s1.get.toList.length > 0)  an[Err] should be thrownBy { runLog(
          s1.get
              .switchMap(_ => f.get)
              .flatMap { _ =>
                Stream.constant(true)
              })
        }
      }
    }
    
  }
}
