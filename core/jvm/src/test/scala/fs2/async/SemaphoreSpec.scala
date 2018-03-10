package fs2
package async

import cats.Parallel
import cats.effect.IO
import cats.implicits._

import TestUtil._

class SemaphoreSpec extends Fs2Spec {

  "Semaphore" - {

    "decrement n synchronously" in {
      forAll { (s: PureStream[Int], n: Int) =>
        val n0 = ((n.abs % 20) + 1).abs
        Stream
          .eval(async.mutable.Semaphore[IO](n0))
          .flatMap { s =>
            Stream
              .emits(0 until n0)
              .evalMap { _ =>
                s.decrement
              }
              .drain ++ Stream.eval(s.available)
          }
          .compile
          .toVector
          .unsafeRunSync() shouldBe Vector(0)
      }
    }

    "offsetting increment/decrements" in {
      forAll { (ms0: Vector[Int]) =>
        val s = async.mutable.Semaphore[IO](0).unsafeRunSync()
        val longs = ms0.map(_.toLong.abs)
        val longsRev = longs.reverse
        val t: IO[Unit] = for {
          // just two parallel tasks, one incrementing, one decrementing
          decrs <- async.shiftStart { longs.traverse(s.decrementBy) }
          incrs <- async.shiftStart { longsRev.traverse(s.incrementBy) }
          _ <- decrs.join: IO[Vector[Unit]]
          _ <- incrs.join: IO[Vector[Unit]]
        } yield ()
        t.unsafeRunSync()
        s.count.unsafeRunSync() shouldBe 0

        val t2: IO[Unit] = for {
          // N parallel incrementing tasks and N parallel decrementing tasks
          decrs <- async.shiftStart { Parallel.parTraverse(longs)(IO.shift *> s.decrementBy(_)) }
          incrs <- async.shiftStart {
            Parallel.parTraverse(longsRev)(IO.shift *> s.incrementBy(_))
          }
          _ <- decrs.join: IO[Vector[Unit]]
          _ <- incrs.join: IO[Vector[Unit]]
        } yield ()
        t2.unsafeRunSync()
        s.count.unsafeRunSync() shouldBe 0
      }
    }
  }
}
