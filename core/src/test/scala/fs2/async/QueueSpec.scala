package fs2
package async

import fs2.util.Task

class QueueSpec extends Fs2Spec {
  "Queue" - {
    "unbounded producer/consumer" in {
      forAll { (s: PureStream[Int]) =>
        withClue(s.tag) {
          runLog(Stream.eval(async.unboundedQueue[Task,Int]).map { q =>
            val r = s.get.toVector
            runLog(q.dequeue.merge(s.get.evalMap(q.enqueue1).drain).take(r.size)) == r
          }) shouldBe Vector(true)
        }
      }
    }
  }
}
