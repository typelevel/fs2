package fs2

import cats.Eq
import cats.effect.IO
import cats.effect.laws.discipline.arbitrary._
import cats.effect.laws.util.TestContext
import cats.effect.laws.util.TestInstances._
import cats.implicits._
import cats.laws.discipline._
import cats.effect.laws.discipline._

import org.scalacheck.{Arbitrary, Gen}
import Arbitrary.arbitrary

class PullLawsSpec extends Fs2Spec with StreamArbitrary {

  implicit val ec: TestContext = TestContext()

  implicit def arbPull[F[_], O, R](implicit arbR: Arbitrary[R],
                                   arbFr: Arbitrary[F[R]],
                                   arbFo: Arbitrary[F[O]],
                                   arbO: Arbitrary[O],
                                   arbFu: Arbitrary[F[Unit]]): Arbitrary[Pull[F, O, R]] =
    Arbitrary(
      Gen.oneOf(
        arbitrary[R].map(Pull.pure(_)),
        arbitrary[F[R]].map(Pull.eval(_)),
        arbitrary[Stream[F, O]].flatMap(s => arbitrary[R].map(r => s.pull.echo >> Pull.pure(r)))
      ))

  implicit def eqPull[O: Eq, R: Eq]: Eq[Pull[IO, O, R]] = {
    def normalize(p: Pull[IO, O, R]): IO[Vector[Either[O, R]]] =
      p.mapOutput(Either.left(_)).flatMap(r => Pull.output1(Right(r))).stream.compile.toVector

    Eq.instance((x, y) => Eq.eqv(normalize(x), normalize(y)))
  }

  checkAll("Sync[Pull[F, O, ?]]", SyncTests[Pull[IO, Int, ?]].sync[Int, Int, Int])
}
