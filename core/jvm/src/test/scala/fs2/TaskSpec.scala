package fs2

import scala.concurrent.duration._

class TaskSpec extends Fs2Spec{

  "Task" - {

    "traverse evaluates effects in left-to-right order" in {
      var acc = collection.mutable.ListBuffer[Int]()
      val result = Task.traverse((1 to 5).toList)(n => Task.delay(acc += n))
      result.unsafeRunSync()
      acc.toList shouldBe List(1, 2, 3, 4, 5)
    }

    "stack safety" in {
      (0 until 5000).foldLeft(Task.delay(0))((acc, i) => acc.attempt.flatMap { case Left(t) => Task.fail(t); case Right(a) => Task.delay(a) }).unsafeRun
    }

    "Ref" - {

      /**
        * Tests whether set after access causes said access's
        * set to default to no-op as the value in ref has changed
        */
      "Interleaving set and access " in {

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

    "fromAttempt" - {
      "convert failed attempt to failed Task" in {
        val ex = new RuntimeException
        Task.fromAttempt(Left(ex)).unsafeAttemptRun() shouldBe Left(ex)
      }
      "convert successful attempt to successful Task" in {
        Task.fromAttempt(Right(123)).unsafeRun() shouldBe 123
      }
    }

  }
}
