package fs2
package async

import TestUtil._
import fs2.util.Task
import org.scalacheck.Prop._
import org.scalacheck._
import java.util.concurrent.atomic.AtomicLong

object QueueSpec extends Properties("Queue") {

  property("unbounded producer/consumer") = forAll { (s: PureStream[Int]) =>
    s.tag |: Stream.eval(async.unboundedQueue[Task,Int]).map { q =>
      val r = run(s.get)
      run(q.dequeue.merge(s.get.evalMap(q.enqueue1).drain).take(r.size)) == r
    } === Vector(true)
  }

  property("synchronous (1)") = forAll { (s: PureStream[Int]) =>
    s.tag |: {
      val q = async.synchronousQueue[Task,Int].run
      val out = Stream.repeatEval(q.dequeue1)
      val in = s.get.evalMap(q.enqueue1)
      val r = run(s.get)
      run(out.merge(in.drain).take(r.size)) ?= r
    }
  }

  property("synchronous (2)") = forAll { (vs: List[Int]) =>
    val T = implicitly[Async[Task]]
    val q = async.synchronousQueue[Task,Int].run
    // test should fail occasionally with this -
    // val q = async.boundedQueue[Task,Int](1).run
    val finished = new AtomicLong(0)
    Task.start {
      T.parallelTraverse(vs) { v =>
        q.enqueue1(v).map { _ => finished.incrementAndGet }
      }
    }.run
    Thread.sleep(10)
    // verifies no enqueues have completed (are all waiting for offsetting dequeues)
    val ok1 = finished.get ?= 0
    // produce the offsetting dequeues, making sure all the enqueues come through
    val out = T.traverse(vs) { _ => q.dequeue1 }.run
    ok1 && out.toSet == vs.toSet
  }
}
