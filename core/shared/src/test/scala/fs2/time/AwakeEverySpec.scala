package fs2
package time

import scala.concurrent.duration._
import cats.effect.IO
import org.scalatest.Succeeded

class AwakeEverySpec extends AsyncFs2Spec {

  "time" - {

    "awakeEvery" in {
      runLogF(time.awakeEvery[IO](500.millis).map(_.toMillis).take(5)).map { r =>
        r.toList.sliding(2).map { s => (s.head, s.tail.head) }.map { case (prev, next) => next - prev }.foreach { delta =>
          delta shouldBe 500L +- 100
        }
        Succeeded
      }
    }

    "awakeEvery liveness" in {
      val s = time.awakeEvery[IO](1.milli).evalMap { i => IO.async[Unit](cb => executionContext.execute(() => cb(Right(())))) }.take(200)
      runLogF { Stream(s, s, s, s, s).join(5) }.map { _ => Succeeded }
    }
  }
}
