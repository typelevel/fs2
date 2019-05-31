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
          .stream
          .compile
          .toList
          .flatMap(_ => ref.get)
          .asserting(_ shouldBe 1)
      }
    }

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
