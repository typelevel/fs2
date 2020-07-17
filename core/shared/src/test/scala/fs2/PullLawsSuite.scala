package fs2

import cats.Eq
import cats.effect.IO
import cats.effect.laws.discipline.arbitrary._
import cats.effect.laws.util.TestContext
import cats.effect.laws.util.TestInstances._
import cats.implicits._
import cats.effect.laws.discipline._

class PullLawsSuite extends Fs2Suite {
  implicit val ec: TestContext = TestContext()

  implicit def eqPull[O: Eq, R: Eq]: Eq[Pull[IO, O, R]] = {
    def normalize(p: Pull[IO, O, R]): IO[Vector[Either[O, R]]] =
      p.mapOutput(Either.left(_)).flatMap(r => Pull.output1(Right(r))).stream.compile.toVector

    Eq.instance((x, y) => Eq.eqv(normalize(x), normalize(y)))
  }

  checkAll("Sync[Pull[F, O, *]]", SyncTests[Pull[IO, Int, *]].sync[Int, Int, Int])
}
