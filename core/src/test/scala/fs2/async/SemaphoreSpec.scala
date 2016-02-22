package fs2
package async

import TestUtil.{S => _, _}
import fs2.util.Task
import org.scalacheck.Prop._
import org.scalacheck._

object SemaphoreSpec extends Properties("SemaphoreSpec") {

  implicit val S: Strategy =
    fs2.Strategy.fromCachedDaemonPool("SemaphoreSpec")

  property("decrement n synchronously") = forAll { (s: PureStream[Int], n: Int) =>
    val n0 = ((n.abs % 20) + 1).abs
    Stream.eval(async.mutable.Semaphore[Task](n0)).flatMap { s =>
      Stream.emits(0 until n0).evalMap { _ => s.decrement }.drain ++ Stream.eval(s.available)
    } === Vector(0)
  }

  property("offsetting increment/decrements") = forAll { (ms0: Vector[Int]) =>
    val T = implicitly[Async[Task]]
    val s = async.mutable.Semaphore[Task](0).run
    val longs = ms0.map(_.toLong.abs)
    val longsRev = longs.reverse
    val t: Task[Unit] = for {
      // just two parallel tasks, one incrementing, one decrementing
      decrs <- Task.start { T.traverse(longs)(s.decrementBy) }
      incrs <- Task.start { T.traverse(longsRev)(s.incrementBy) }
      _ <- decrs: Task[Vector[Unit]]
      _ <- incrs: Task[Vector[Unit]]
    } yield ()
    t.run
    val ok1 = "two threads" |: (s.count.run == 0)
    val t2: Task[Unit] = for {
      // N parallel incrementing tasks and N parallel decrementing tasks
      decrs <- Task.start { T.parallelTraverse(longs)(s.decrementBy) }
      incrs <- Task.start { T.parallelTraverse(longsRev)(s.incrementBy) }
      _ <- decrs: Task[Vector[Unit]]
      _ <- incrs: Task[Vector[Unit]]
    } yield ()
    t2.run
    val ok2 = "N threads" |: (s.count.run == 0)
    ok1 && ok2
  }
}
