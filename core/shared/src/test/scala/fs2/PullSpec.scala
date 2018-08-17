package fs2

import cats.effect._
import org.scalatest.Inside

class PullSpec extends Fs2Spec with Inside {

  "Pull" - {

    "fromEither" in forAll { either: Either[Throwable, Int] =>
      val pull: Pull[IO, Int, Unit] = Pull.fromEither[IO](either)

      either match {
        case Left(_) ⇒ pull.stream.compile.toList.attempt.unsafeRunSync() shouldBe either
        case Right(_) ⇒
          pull.stream.compile.toList.unsafeRunSync() shouldBe either.right.toSeq.toList
      }
    }

  }

}
