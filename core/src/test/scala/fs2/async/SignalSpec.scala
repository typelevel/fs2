package fs2
package async

import TestUtil._
import fs2.util.Task
import fs2.Stream.Handle
import java.util.concurrent.atomic.AtomicLong
import org.scalacheck.Prop._
import org.scalacheck._

object SignalSpec extends Properties("Signal") {

  property("get/set/discrete") = forAll { (vs0: List[Long]) =>
    val vs = vs0 map { n => if (n == 0) 1 else n }
    val s = async.signalOf[Task,Long](0L).run
    val r = new AtomicLong(0)
    val u = s.discrete.map(r.set).run.run.runFullyAsyncFuture
    vs.forall { v =>
      s.set(v).run
      while (s.get.run != v) {} // wait for set to arrive
      // can't really be sure when the discrete subscription will be set up,
      // but once we've gotten one update (r != 0), we know we're subscribed
      // and should see result of all subsequent calls to set
      if (r.get != 0) { while (r.get != v) {} }
      true
    }
  }
}

