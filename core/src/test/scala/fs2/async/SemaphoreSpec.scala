package fs2
package async

import TestUtil._
import fs2.util.Task
import org.scalacheck.Prop._
import org.scalacheck._

object SemaphoreSpec extends Properties("SemaphoreSpec") {

  property("decrement n synchronously") = forAll { (s: PureStream[Int], n: Int) =>
    val n0 = ((n.abs % 20) + 1).abs
    println(s"decrement semaphore($n0) $n0 times test")
    Stream.eval(async.mutable.Semaphore[Task](n0)).flatMap { s =>
      Stream.emits(0 until n0).evalMap { _ => s.decrement }.drain ++ Stream.eval(s.available)
    } === Vector(0)
  }
}
