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

trait StreamArbitrary {
  implicit def arbStream[F[_], O](
      implicit arbO: Arbitrary[O],
      arbFo: Arbitrary[F[O]],
      arbFu: Arbitrary[F[Unit]]
  ): Arbitrary[Stream[F, O]] =
    Arbitrary(
      Gen.frequency(
        10 -> arbitrary[List[O]].map(os => Stream.emits(os).take(10)),
        10 -> arbitrary[List[O]].map(os => Stream.emits(os).take(10).unchunk),
        5 -> arbitrary[F[O]].map(fo => Stream.eval(fo)),
        1 -> (for {
          acquire <- arbitrary[F[O]]
          release <- arbitrary[F[Unit]]
          use <- arbStream[F, O].arbitrary
        } yield Stream.bracket(acquire)(_ => release).flatMap(_ => use))
      )
    )

  implicit def arbZipStream[F[_], O](
      implicit arbStream: Arbitrary[Stream[F, O]]
  ): Arbitrary[Stream.ZipStream[F, O]] =
    Arbitrary(arbStream.arbitrary.map(Stream.ZipStream(_)))

}

class StreamLawsSpec extends Fs2Spec with StreamArbitrary {

  implicit val ec: TestContext = TestContext()

  implicit def eqStream[O: Eq]: Eq[Stream[IO, O]] =
    Eq.instance(
      (x, y) =>
        Eq[IO[Vector[Either[Throwable, O]]]]
          .eqv(x.attempt.compile.toVector, y.attempt.compile.toVector)
    )

  implicit def eqZipStream[F[_], O](
      implicit eqStream: Eq[Stream[F, O]]
  ): Eq[Stream.ZipStream[F, O]] =
    Eq.by(_.underlying)

  checkAll(
    "MonadError[Stream[F, ?], Throwable]",
    MonadErrorTests[Stream[IO, ?], Throwable].monadError[Int, Int, Int]
  )
  checkAll(
    "FunctorFilter[Stream[F, ?]]",
    FunctorFilterTests[Stream[IO, ?]].functorFilter[String, Int, Int]
  )
  checkAll("MonoidK[Stream[F, ?]]", MonoidKTests[Stream[IO, ?]].monoidK[Int])
  checkAll(
    "NonEmptyParalell[Stream[F, ?]]",
    NonEmptyParallelTests[Stream[IO, ?], Stream.ZipStream[IO, ?]].nonEmptyParallel[Int, String]
  )
  checkAll(
    "CommutativeApply[ZipStream[F, ?]]",
    CommutativeApplyTests[Stream.ZipStream[IO, ?]].commutativeApply[Int, String, Boolean]
  )
}
