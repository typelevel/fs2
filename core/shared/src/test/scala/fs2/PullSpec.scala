package fs2

import cats.Eq
import cats.implicits._
import cats.effect.IO
import cats.effect.laws.discipline.SyncTests
import cats.effect.laws.discipline.arbitrary._
import cats.effect.laws.util.TestContext
import cats.effect.laws.util.TestInstances._

import org.scalacheck.{Arbitrary, Gen, Cogen}
import Arbitrary.arbitrary

import TestUtil._

class PullSpec extends Fs2Spec {

  implicit val tc: TestContext = TestContext()

  implicit def arbPull[O: Arbitrary, R: Arbitrary: Cogen]: Arbitrary[Pull[IO, O, R]] =
    Arbitrary(
      Gen.oneOf(
        arbitrary[R].map(Pull.pure(_)),
        arbitrary[IO[R]].map(Pull.eval(_)),
        arbitrary[PureStream[O]].flatMap(s => arbitrary[R].map(r => s.get.pull.echo >> Pull.pure(r)))
      )
    )

  implicit def eqPull[O: Eq, R: Eq]: Eq[Pull[IO, O, R]] = {
    def normalize(p: Pull[IO, O, R]): IO[Vector[Either[O, R]]] =
      p.mapOutput(Either.left(_)).flatMap(r => Pull.output1(Right(r))).stream.compile.toVector

    Eq.instance((x, y) => Eq.eqv(normalize(x), normalize(y)))
  }

  "Pull" - {
    checkAll("Sync[Pull[F, O, ?]]", SyncTests[Pull[IO, Int, ?]].sync[Int, Int, Int])

    "fromEither" in forAll { either: Either[Throwable, Int] =>
      val pull: Pull[IO, Int, Unit] = Pull.fromEither[IO](either)

      either match {
        case Left(_) => pull.stream.compile.toList.attempt.unsafeRunSync() shouldBe either
        case Right(_) =>
          pull.stream.compile.toList.unsafeRunSync() shouldBe either.toList
      }
    }

  }
}
