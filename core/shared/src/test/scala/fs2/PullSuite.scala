package fs2

import cats.effect.IO
import cats.effect.concurrent.Ref
import org.scalacheck.Prop.forAll

class PullSuite extends Fs2Suite {
  test("early termination of uncons") {
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
        .map(it => assert(it == 1))
    }
  }

  property("fromEither") {
    forAll { either: Either[Throwable, Int] =>
      val pull: Pull[Fallible, Int, Unit] = Pull.fromEither[Fallible](either)

      either match {
        case Left(l) => assert(pull.stream.compile.toList == Left(l))
        case Right(r) =>
          assert(pull.stream.compile.toList == Right(List(r)))
      }
    }
  }
}
