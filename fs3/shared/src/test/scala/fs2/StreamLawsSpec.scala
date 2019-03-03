package fs2

import cats.Eq
import cats.effect.IO
import cats.effect.laws.discipline.arbitrary._
import cats.effect.laws.util.TestContext
import cats.effect.laws.util.TestInstances._
import cats.implicits._
import cats.laws.discipline._

import org.scalacheck.{Arbitrary, Gen}
import Arbitrary.arbitrary

class StreamLawsSpec extends LawsSpec with StreamGenerators {
  implicit val ec: TestContext = TestContext()

  implicit def arbStream[F[_], O](implicit arbO: Arbitrary[O],
                                  arbFo: Arbitrary[F[O]]): Arbitrary[Stream[F, O]] =
    Arbitrary(
      Gen.frequency(
        4 -> arbitrary[List[O]].map(os => Stream.emits(os).take(10)),
        4 -> arbitrary[List[O]].map(os => Stream.emits(os).take(10).unchunk),
        2 -> arbitrary[F[O]].map(fo => Stream.eval(fo))
      ))

  implicit def eqStream[O: Eq]: Eq[Stream[IO, O]] =
    Eq.instance(
      (x, y) =>
        Eq[IO[Vector[Either[Throwable, O]]]]
          .eqv(x.attempt.compile.toVector, y.attempt.compile.toVector))

  checkAll("MonadError[Stream[F, ?], Throwable]",
           MonadErrorTests[Stream[IO, ?], Throwable].monadError[Int, Int, Int])
  checkAll("FunctorFilter[Stream[F, ?]]",
           FunctorFilterTests[Stream[IO, ?]].functorFilter[String, Int, Int])
  checkAll("MonoidK[Stream[F, ?]]", MonoidKTests[Stream[IO, ?]].monoidK[Int])
}
