package fs2
package util

import cats.effect.IO

import scala.concurrent.duration._

class ConcurrentSpec extends Fs2Spec {

    "Ref" - {

      /**
        * Tests whether set after access causes said access's
        * set to default to no-op as the value in ref has changed
        */
      "Interleaving set and access " in {

        Concurrent[IO].ref[Int].flatMap{ref =>
          ref.setPure(1).flatMap{_ =>
            ref.access.flatMap{case ((_, set)) =>
              ref.setPure(2).flatMap{ _ =>
                set(Right(3)).shift(scheduler.delayedExecutionContext(100.millis))
              }
            }
          }
        }.unsafeToFuture.map { _ shouldBe false }
      }
    }

  }
