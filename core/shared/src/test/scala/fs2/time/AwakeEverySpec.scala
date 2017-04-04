package fs2
package time

import scala.concurrent.duration._
import org.scalatest.Succeeded
import fs2.util.ExecutionContexts._

class AwakeEverySpec extends AsyncFs2Spec {

  "time" - {

    "awakeEvery" in {
      runLogF(time.awakeEvery[Task](500.millis).map(_.toMillis).take(5)).map { r =>
        r.toList.sliding(2).map { s => (s.head, s.tail.head) }.map { case (prev, next) => next - prev }.foreach { delta =>
          delta shouldBe 500L +- 100
        }
        Succeeded
      }
    }

    "awakeEvery liveness" in {
      val s = time.awakeEvery[Task](1.milli).evalMap { i => Task.async[Unit](cb => executionContext.executeThunk(cb(Right(())))) }.take(200)
      runLogF { concurrent.join(5)(Stream(s, s, s, s, s)) }.map { _ => Succeeded }
    }
  }
}
