package fs2
package async

import scala.concurrent.duration._

import cats.effect.IO
import cats.implicits._

class SemaphoreSpec extends Fs2Spec {

  "Semaphore" - {

    "decrement n synchronously" in {
      forAll { (s: PureStream[Int], n: Int) =>
        val n0 = ((n.abs % 20) + 1).abs
        Stream.eval(async.mutable.Semaphore[IO](n0)).flatMap { s =>
          Stream.emits(0 until n0).evalMap { _ => s.decrement }.drain ++ Stream.eval(s.available)
        }.runLog.unsafeRunSync() shouldBe Vector(0)
      }
    }

    "offsetting increment/decrements" in {
      forAll { (ms0: Vector[Int]) =>
        val s = async.mutable.Semaphore[IO](0).unsafeRunSync()
        val longs = ms0.map(_.toLong.abs)
        val longsRev = longs.reverse
        val t: IO[Unit] = for {
          // just two parallel tasks, one incrementing, one decrementing
          decrs <- async.start { longs.traverse(s.decrementBy) }
          incrs <- async.start { longsRev.traverse(s.incrementBy) }
          _ <- decrs: IO[Vector[Unit]]
          _ <- incrs: IO[Vector[Unit]]
        } yield ()
        t.unsafeRunSync()
        s.count.unsafeRunSync() shouldBe 0

        val t2: IO[Unit] = for {
          // N parallel incrementing tasks and N parallel decrementing tasks
          decrs <- async.start { async.parallelTraverse(longs)(s.decrementBy) }
          incrs <- async.start { async.parallelTraverse(longsRev)(s.incrementBy) }
          _ <- decrs: IO[Vector[Unit]]
          _ <- incrs: IO[Vector[Unit]]
        } yield ()
        t2.unsafeRunSync()
        s.count.unsafeRunSync() shouldBe 0
      }
    }

    "timedDecrement" in {
      runLog(Scheduler[IO](1).flatMap { scheduler =>
        Stream.eval(
          for {
            s <- async.semaphore[IO](1)
            first <- s.timedDecrement(100.millis, scheduler)
            second <- s.timedDecrement(100.millis, scheduler)
            _ <- s.increment
            third <- s.timedDecrement(100.millis, scheduler)
          } yield List(first, second, third)
        )
      }).flatten shouldBe Vector(true, false, true)
    }

    "timedDecrementBy" in {
      runLog(Scheduler[IO](1).flatMap { scheduler =>
        Stream.eval(
          for {
            s <- async.semaphore[IO](7)
            first <- s.timedDecrementBy(5, 100.millis, scheduler)
            second <- s.timedDecrementBy(5, 100.millis, scheduler)
            _ <- s.incrementBy(10)
            third <- s.timedDecrementBy(5, 100.millis, scheduler)
          } yield List(first, second, third)
        )
      }).flatten shouldBe Vector(0, 3, 0)
    }
  }
}
