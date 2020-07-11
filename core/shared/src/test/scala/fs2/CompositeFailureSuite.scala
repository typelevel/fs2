package fs2

import cats.data.NonEmptyList

import scala.util.control.NoStackTrace

class CompositeFailureSuite extends Fs2Suite {
  test("flatten nested exceptions that's one level deep") {
    def err(i: Int) = new RuntimeException(i.toString) with NoStackTrace
    val compositeFailure = CompositeFailure(
      CompositeFailure(err(1), err(2)),
      err(3),
      List(CompositeFailure(err(4), err(5)))
    )
    assert(compositeFailure.all.map(_.getMessage) == NonEmptyList.of("1", "2", "3", "4", "5"))
    assert(compositeFailure.all.collect { case cf: CompositeFailure => cf }.isEmpty)
  }
}
