package fs2
package async

import fs2.util.Task
import java.util.concurrent.atomic.AtomicLong

class SignalSpec extends Fs2Spec {
  "Signal" - {
    "get/set/discrete" in {
      forAll { (vs0: List[Long]) =>
        val vs = vs0 map { n => if (n == 0) 1 else n }
        val s = async.signalOf[Task,Long](0L).unsafeRun
        val r = new AtomicLong(0)
        val u = s.discrete.map(r.set).run.run.async.unsafeRunAsyncFuture
        assert(vs.forall { v =>
          s.set(v).unsafeRun
          while (s.get.unsafeRun != v) {} // wait for set to arrive
          // can't really be sure when the discrete subscription will be set up,
          // but once we've gotten one update (r != 0), we know we're subscribed
          // and should see result of all subsequent calls to set
          if (r.get != 0) { while (r.get != v) {} }
          true
        })
      }
    }
  }
}

