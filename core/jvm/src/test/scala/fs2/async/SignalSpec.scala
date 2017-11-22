package fs2
package async

import java.util.concurrent.atomic.AtomicLong
import cats.effect.IO
import cats.implicits._

class SignalSpec extends Fs2Spec {
  "Signal" - {
    "get/set/discrete" in {
      forAll { (vs0: List[Long]) =>
        val vs = vs0 map { n => if (n == 0) 1 else n }
        val s = async.signalOf[IO,Long](0L).unsafeRunSync()
        val r = new AtomicLong(0)
        (IO.shift *> s.discrete.map(r.set).run).unsafeToFuture()
        assert(vs.forall { v =>
          s.set(v).unsafeRunSync()
          while (s.get.unsafeRunSync() != v) {} // wait for set to arrive
          // can't really be sure when the discrete subscription will be set up,
          // but once we've gotten one update (r != 0), we know we're subscribed
          // and should see result of all subsequent calls to set
          if (r.get != 0) { while (r.get != v) {} }
          true
        })
      }
    }

    "discrete" in {
      // verifies that discrete always receives the most recent value, even when updates occur rapidly
      forAll { (v0: Long, vsTl: List[Long]) =>
        val vs = v0 :: vsTl
        val s = async.signalOf[IO,Long](0L).unsafeRunSync()
        val r = new AtomicLong(0)
        (IO.shift *> s.discrete.map { i => Thread.sleep(10); r.set(i) }.run).unsafeToFuture()
        vs.foreach { v => s.set(v).unsafeRunSync() }
        val last = vs.last
        while (r.get != last) {}
        true
      }
    }

    "holdOption" in {
      runLog(async.holdOption(Stream.range(1,10).covary[IO]))
    }
  }
}
