package fs2

import java.util.concurrent.atomic.AtomicLong

import org.scalacheck.Gen
import cats.effect.IO
import cats.implicits.{catsSyntaxEither ⇒ _, _}

import scala.concurrent.duration._
import TestUtil._
import fs2.Stream._

class PipeSpec extends Fs2Spec {

  "Pipe" - {
    "tail" in forAll { (s: PureStream[Int]) =>
      runLog(s.get.tail) shouldBe runLog(s.get).drop(1)
    }

    "observe/observeAsync" - {
      "basic functionality" in {
        forAll { (s: PureStream[Int]) =>
          val sum = new AtomicLong(0)
          val out = runLog {
            s.get.covary[IO].observe {
              _.evalMap(i => IO { sum.addAndGet(i.toLong); () })
            }
          }
          out.map(_.toLong).sum shouldBe sum.get
          sum.set(0)
          val out2 = runLog {
            s.get.covary[IO].observeAsync(maxQueued = 10) {
              _.evalMap(i => IO { sum.addAndGet(i.toLong); () })
            }
          }
          out2.map(_.toLong).sum shouldBe sum.get
        }
      }

      "observe is not eager (1)" in {
        //Do not pull another element before we emit the currently processed one
        (Stream.eval(IO(1)) ++ Stream.eval(IO.raiseError(new Throwable("Boom"))))
          .observe(_.evalMap(_ => IO(Thread.sleep(100)))) //Have to do some work here, so that we give time for the underlying stream to try pull more
          .take(1)
          .compile
          .toVector
          .unsafeRunSync shouldBe Vector(1)
      }

      "observe is not eager (2)" in {
        //Do not pull another element before the downstream asks for another
        (Stream.eval(IO(1)) ++ Stream.eval(IO.raiseError(new Throwable("Boom"))))
          .observe(_.drain)
          .flatMap(_ => Stream.eval(IO(Thread.sleep(100))) >> Stream(1, 2)) //Have to do some work here, so that we give time for the underlying stream to try pull more
          .take(2)
          .compile
          .toVector
          .unsafeRunSync shouldBe Vector(1, 2)
      }

    }
    "handle errors from observing sink" in {
      forAll { (s: PureStream[Int]) =>
        val r1 = runLog {
          s.get
            .covary[IO]
            .observe { _ =>
              Stream.raiseError[IO](new Err)
            }
            .attempt
        }
        r1 should have size (1)
        r1.head.fold(identity, r => fail(s"expected left but got Right($r)")) shouldBe an[Err]
        val r2 = runLog {
          s.get
            .covary[IO]
            .observeAsync(2) { _ =>
              Stream.raiseError[IO](new Err)
            }
            .attempt
        }
        r2 should have size (1)
        r2.head.fold(identity, r => fail(s"expected left but got Right($r)")) shouldBe an[Err]
      }
    }

    "propagate error from source" in {
      forAll { (f: Failure) =>
        val r1 = runLog {
          f.get
            .covary[IO]
            .observe(_.drain)
            .attempt
        }
        r1 should have size (1)
        r1.head.fold(identity, r => fail(s"expected left but got Right($r)")) shouldBe an[Err]
        val r2 = runLog {
          f.get
            .covary[IO]
            .observeAsync(2)(_.drain)
            .attempt
        }
        r2 should have size (1)
        r2.head.fold(identity, r => fail(s"expected left but got Right($r)")) shouldBe an[Err]
      }
    }

    "handle finite observing sink" in {
      forAll { (s: PureStream[Int]) =>
        runLog {
          s.get.covary[IO].observe { _ =>
            Stream.empty
          }
        } shouldBe Vector.empty
        runLog {
          s.get.covary[IO].observe { _.take(2).drain }
        }
        runLog {
          s.get.covary[IO].observeAsync(2) { _ =>
            Stream.empty
          }
        } shouldBe Vector.empty
      }
    }
    "handle multiple consecutive observations" in {
      forAll { (s: PureStream[Int], f: Failure) =>
        runLog {
          val sink: Pipe[IO, Int, Unit] = _.evalMap(i => IO(()))
          val src: Stream[IO, Int] = s.get.covary[IO]
          src.observe(sink).observe(sink)
        } shouldBe s.get.toVector
      }
    }
    "no hangs on failures" in {
      forAll { (s: PureStream[Int], f: Failure) =>
        swallow {
          runLog {
            val sink: Pipe[IO, Int, Unit] =
              in => spuriousFail(in.evalMap(i => IO(i)), f).map(_ => ())
            val src: Stream[IO, Int] = spuriousFail(s.get.covary[IO], f)
            src.observe(sink).observe(sink)
          }
        }
      }
    }

  }

}
