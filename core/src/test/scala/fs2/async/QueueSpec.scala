package fs2
package async

import TestUtil._
import fs2.util.Task
import fs2.Stream.Handle
import java.util.concurrent.atomic.AtomicLong
import org.scalacheck.Prop._
import org.scalacheck._

object QueueSpec extends Properties("Queue") {

  property("unbounded producer/consumer") = forAll { (s: PureStream[Int]) =>
    s.tag |: Stream.eval(async.unboundedQueue[Task,Int]).map { q =>
      val r = run(s.get)
      run(q.dequeue.merge(s.get.evalMap(q.enqueue1).drain).take(r.size)) == r
    } === Vector(true)
  }
}
