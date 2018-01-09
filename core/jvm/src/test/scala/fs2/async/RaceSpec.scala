package fs2
package async

import cats.effect.IO
import scala.concurrent.duration._
import org.scalatest.EitherValues

import TestUtil._

class RaceSpec extends AsyncFs2Spec with EitherValues {
  "Successful race" in {
    val stream = mkScheduler.evalMap { s =>
      async.race(
        s.effect.sleep[IO](20.millis),
        s.effect.sleep[IO](200.millis)
      )
    }

    runLogF(stream).map { x =>
      x.head shouldBe ('left)
    }
  }

  "Unsuccessful race" in {
    val stream = mkScheduler.evalMap { s =>
      async
        .race(
          s.effect.sleep[IO](4.seconds),
          IO.raiseError[Unit](new Exception)
        )
        .attempt
    }

    runLogF(stream).map { x =>
      x.head.left.value shouldBe a[Exception]
    }
  }
}
