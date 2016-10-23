package fs2

import scala.concurrent.duration._

class TaskSpec extends Fs2Spec{

  "Task" - {
    "Ref" - {
      "Set increments nonce" in {

        Task.ref[Int].flatMap{ref =>
          ref.setPure(1).flatMap{_ =>
            ref.access.flatMap{case ((_, set)) =>
              ref.setPure(2).flatMap{_ =>
                set(Right(3)).schedule(100.millis)
              }
            }
          }
        }.unsafeRun() shouldBe false
      }
    }
  }
}
