package fs2
package async

import cats.effect.IO
import cats.implicits._

import scala.concurrent.duration._

class RefSpec extends Fs2Spec {

  "Ref" - {

    /**
      * Tests whether set after access causes said access's
      * set to default to no-op as the value in ref has changed
      */
    "Interleaving set and access " in {

      ref[IO, Int].flatMap{ ref =>
        ref.setAsyncPure(1).flatMap{ _ =>
          ref.access.flatMap{ case ((_, set)) =>
            ref.setAsyncPure(2).flatMap{ _ =>
              IO.shift(scheduler.delayedExecutionContext(100.millis)) >> set(Right(3))
            }
          }
        }
      }.unsafeToFuture.map { _ shouldBe false }
    }

    "setSync" in {
      ref[IO, Int].flatMap { ref =>
        ref.setSyncPure(0) >> ref.setSync(IO(1)) >> ref.get
      }.unsafeToFuture.map { _ shouldBe 1 }
    }
  }
}
