package fs2
package async

import fs2.util.Task

class SemaphoreSpec extends Fs2Spec {

  override implicit val S: Strategy = fs2.Strategy.fromCachedDaemonPool("SemaphoreSpec")

  "Semaphore" - {

    "decrement n synchronously" in {
      forAll { (s: PureStream[Int], n: Int) =>
        val n0 = ((n.abs % 20) + 1).abs
        Stream.eval(async.mutable.Semaphore[Task](n0)).flatMap { s =>
          Stream.emits(0 until n0).evalMap { _ => s.decrement }.drain ++ Stream.eval(s.available)
        }.runLog.run.unsafeRun shouldBe Vector(0)
      }
    }

    "offsetting increment/decrements" in {
      forAll { (ms0: Vector[Int]) =>
        val T = implicitly[Async[Task]]
        val s = async.mutable.Semaphore[Task](0).unsafeRun
        val longs = ms0.map(_.toLong.abs)
        val longsRev = longs.reverse
        val t: Task[Unit] = for {
          // just two parallel tasks, one incrementing, one decrementing
          decrs <- Task.start { T.traverse(longs)(s.decrementBy) }
          incrs <- Task.start { T.traverse(longsRev)(s.incrementBy) }
          _ <- decrs: Task[Vector[Unit]]
          _ <- incrs: Task[Vector[Unit]]
        } yield ()
        t.unsafeRun
        s.count.unsafeRun shouldBe 0

        val t2: Task[Unit] = for {
          // N parallel incrementing tasks and N parallel decrementing tasks
          decrs <- Task.start { T.parallelTraverse(longs)(s.decrementBy) }
          incrs <- Task.start { T.parallelTraverse(longsRev)(s.incrementBy) }
          _ <- decrs: Task[Vector[Unit]]
          _ <- incrs: Task[Vector[Unit]]
        } yield ()
        t2.unsafeRun
        s.count.unsafeRun shouldBe 0
      }
    }
  }
}
