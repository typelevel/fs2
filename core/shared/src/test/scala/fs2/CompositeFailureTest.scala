package fs2

import cats.data.NonEmptyList

import scala.util.control.NoStackTrace

class CompositeFailureTest extends Fs2Spec {
  "CompositeFailure" - {
    "flatten nested exceptions that's one level deep" in {
      def err(i: Int) = new RuntimeException(i.toString) with NoStackTrace
      val compositeFailure = CompositeFailure(
        CompositeFailure(err(1), err(2)),
        err(3),
        List(CompositeFailure(err(4), err(5)))
      )
      compositeFailure.all.map(_.getMessage) shouldBe NonEmptyList.of("1", "2", "3", "4", "5")
      compositeFailure.all.collect { case cf: CompositeFailure => cf } shouldBe empty
    }
  }
}
