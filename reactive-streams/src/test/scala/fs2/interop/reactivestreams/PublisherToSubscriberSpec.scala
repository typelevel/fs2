package fs2
package interop
package reactivestreams

import cats.effect._
import org.scalacheck.Prop.forAll

final class PublisherToSubscriberSpec extends Fs2Suite {

  test("should have the same output as input") {
    forAll { (ints: Seq[Int]) =>
      val subscriberStream = Stream.emits(ints).covary[IO].toUnicastPublisher.toStream[IO]

      assert(subscriberStream.compile.toVector.unsafeRunSync() == (ints.toVector))
    }
  }

  object TestError extends Exception("BOOM")

  test("should propagate errors downstream") {
    val input: Stream[IO, Int] = Stream(1, 2, 3) ++ Stream.raiseError[IO](TestError)
    val output: Stream[IO, Int] = input.toUnicastPublisher.toStream[IO]

    assert(output.compile.drain.attempt.unsafeRunSync() == (Left(TestError)))
  }

  test("should cancel upstream if downstream completes") {
    forAll { (as: Seq[Int], bs: Seq[Int]) =>
      val subscriberStream =
        Stream.emits(as ++ bs).covary[IO].toUnicastPublisher.toStream[IO].take(as.size)

      assert(subscriberStream.compile.toVector.unsafeRunSync() == (as.toVector))
    }
  }
}
