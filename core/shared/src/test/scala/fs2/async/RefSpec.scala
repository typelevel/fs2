package fs2
package async

import cats.effect.IO
import cats.implicits._

import scala.concurrent.duration._

class RefSpec extends Fs2Spec {

  "Ref" - {

    /* Tests whether set after access causes said access's
     * set to default to no-op as the value in ref has changed */
    "Interleaving set and access " in {

      ref[IO, Int].flatMap{ ref =>
        ref.setAsyncPure(1).flatMap{ _ =>
          ref.access.flatMap{ case ((_, set)) =>
            ref.setAsyncPure(2).flatMap { _ =>
              mkScheduler.runLast.map(_.get).flatMap { scheduler =>
                IO.shift(scheduler.delayedExecutionContext(100.millis)) *> set(Right(3))
              }
            }
          }
        }
      }.unsafeToFuture.map { _ shouldBe false }
    }

    "setSync" in {
      ref[IO, Int].flatMap { ref =>
        ref.setSyncPure(0) *> ref.setSync(IO(1)) *> ref.get
      }.unsafeToFuture.map { _ shouldBe 1 }
    }

    "timedGet" in {
      mkScheduler.flatMap { scheduler =>
        Stream.eval(
          for {
            r <- async.ref[IO,Int]
            first <- r.timedGet(100.millis, scheduler)
            _ <- r.setSyncPure(42)
            second <- r.timedGet(100.millis, scheduler)
          } yield List(first, second)
        )
      }.runLog.unsafeToFuture.map(_.flatten shouldBe Vector(None, Some(42)))
    }
  }
}
