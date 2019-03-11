package fs2

import cats.effect.IO
import cats.effect.concurrent.Ref

class PullSpec extends Fs2Spec {
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
}
