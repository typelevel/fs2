package fs2

class TaskSpec extends Fs2Spec{

  "Task" - {

    "traverse evaluates effects in left-to-right order" in {
      var acc = collection.mutable.ListBuffer[Int]()
      val result = Task.traverse((1 to 5).toList)(n => Task.delay(acc += n))
      result.unsafeRunSync()
      acc.toList shouldBe List(1, 2, 3, 4, 5)
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
