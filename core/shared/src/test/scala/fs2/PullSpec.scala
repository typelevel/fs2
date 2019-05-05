package fs2

import cats.effect.IO
import cats.effect.concurrent.Ref

class PullSpec extends Fs2Spec {
  "Pull" - {
    "early termination of uncons" in {
      Ref.of[IO, Int](0).flatMap { ref =>
        Stream(1, 2, 3)
          .onFinalize(ref.set(1))
          .pull
          .echoChunk
          .void
          .stream
          .compile
          .toList
          .flatMap(_ => ref.get)
          .asserting(_ shouldBe 1)
      }
    }

    // TODO
    // implicit val tc: TestContext = TestContext()

    // implicit def arbPull[O: Arbitrary, R: Arbitrary: Cogen]: Arbitrary[Pull[IO, O, R]] =
    //   Arbitrary(
    //     Gen.oneOf(
    //       arbitrary[R].map(Pull.pure(_)),
    //       arbitrary[IO[R]].map(Pull.eval(_)),
    //       arbitrary[PureStream[O]].flatMap(s =>
    //         arbitrary[R].map(r => s.get.pull.echo >> Pull.pure(r)))
    //     )
    //   )

    // implicit def eqPull[O: Eq, R: Eq]: Eq[Pull[IO, O, R]] = {
    //   def normalize(p: Pull[IO, O, R]): IO[Vector[Either[O, R]]] =
    //     p.mapOutput(Either.left(_)).flatMap(r => Pull.output1(Right(r))).stream.compile.toVector

    //   Eq.instance((x, y) => Eq.eqv(normalize(x), normalize(y)))
    // }

    // checkAll("Sync[Pull[F, O, ?]]", SyncTests[Pull[IO, Int, ?]].sync[Int, Int, Int])

    "fromEither" in forAll { either: Either[Throwable, Int] =>
      val pull: Pull[Fallible, Int, Unit] = Pull.fromEither[Fallible](either)

      either match {
        case Left(l) => pull.stream.compile.toList shouldBe Left(l)
        case Right(r) =>
          pull.stream.compile.toList shouldBe Right(List(r))
      }
    }
  }
}
