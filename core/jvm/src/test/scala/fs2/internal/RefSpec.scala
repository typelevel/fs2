package fs2
package internal

import org.scalacheck._
import java.util.concurrent.CountDownLatch
import fs2.util.ExecutionContexts._

class RefSpec extends Fs2Spec {

  "Ref" - {
    "modify" in {
      forAll(Gen.choose(1,100)) { n =>
        val ref = Ref(0)
        val latch = new CountDownLatch(2)
        executionContext.executeThunk { (0 until n).foreach { _ => ref.modify(_ + 1) }; latch.countDown }
        executionContext.executeThunk { (0 until n).foreach { _ => ref.modify(_ - 1) }; latch.countDown }
        latch.await
        ref.get shouldBe 0
      }
    }
  }
}
