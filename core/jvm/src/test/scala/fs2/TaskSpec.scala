package fs2

/**
  * Created by adamchlupacek on 22/10/16.
  */
class TaskSpec extends Fs2Spec{

  "Task" - {
    "Ref" - {
      "Set increments nonce" in {

        Task.ref[Int].flatMap{ref =>
          ref.setPure(1).flatMap{_ =>
            ref.access.flatMap{case ((_, set)) =>
              ref.setPure(2).flatMap{_ =>
                set(Right(3))
              }

            }
          }

        }.unsafeRun() shouldBe false
      }
    }
  }
}
