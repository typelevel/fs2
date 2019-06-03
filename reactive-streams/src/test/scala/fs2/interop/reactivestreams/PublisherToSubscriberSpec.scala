package fs2
package interop
package reactivestreams

import cats.effect._
import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

final class PublisherToSubscriberSpec
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckPropertyChecks {

  implicit val ctx: ContextShift[IO] =
    IO.contextShift(scala.concurrent.ExecutionContext.Implicits.global)

  it should "have the same output as input" in {
    forAll { (ints: Seq[Int]) =>
      val subscriberStream = Stream.emits(ints).covary[IO].toUnicastPublisher.toStream[IO]

      subscriberStream.compile.toVector.unsafeRunSync() should ===(ints.toVector)
    }
  }

  object TestError extends Exception("BOOM")

  it should "propagate errors downstream" in {
    val input: Stream[IO, Int] = Stream(1, 2, 3) ++ Stream.raiseError[IO](TestError)
    val output: Stream[IO, Int] = input.toUnicastPublisher.toStream[IO]

    output.compile.drain.attempt.unsafeRunSync() should ===(Left(TestError))
  }

  it should "cancel upstream if downstream completes" in {
    forAll { (as: Seq[Int], bs: Seq[Int]) =>
      val subscriberStream =
        Stream.emits(as ++ bs).covary[IO].toUnicastPublisher.toStream[IO].take(as.size)

      subscriberStream.compile.toVector.unsafeRunSync() should ===(as.toVector)
    }
  }
}
