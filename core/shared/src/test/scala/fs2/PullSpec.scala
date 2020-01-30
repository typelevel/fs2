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
          .asserting(it => assert(it == 1))
      }
    }

    "fromEither" in forAll { either: Either[Throwable, Int] =>
      val pull: Pull[Fallible, Int, Unit] = Pull.fromEither[Fallible](either)

      either match {
        case Left(l) => assert(pull.stream.compile.toList == Left(l))
        case Right(r) =>
          assert(pull.stream.compile.toList == Right(List(r)))
      }
    }
  }
}
