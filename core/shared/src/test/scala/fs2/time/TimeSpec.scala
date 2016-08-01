package fs2
package time

import scala.concurrent.duration._
import org.scalatest.Succeeded

class TimeSpec extends AsyncFs2Spec {

  "time" - {

    "awakeEvery" in {
      runLogF(time.awakeEvery[Task](500.millis).map(_.toMillis/500).take(5)).map {
        _ shouldBe Vector(1,2,3,4,5)
      }
    }

    "awakeEvery liveness" in {
      val strategy = implicitly[Strategy]
      val s = time.awakeEvery[Task](1.milli).evalMap { i => Task.async[Unit](cb => strategy(cb(Right(())))) }.take(200)
      runLogF { concurrent.join(5)(Stream(s, s, s, s, s)) }.map { _ => Succeeded }
    }

    "duration" in {
      time.duration[Task].take(1).runLog.unsafeRunAsyncFuture().map { _.last }.map { firstValueDiscrepancy =>
        firstValueDiscrepancy.toNanos should be < (200.millis.toNanos)
      }
    }

    "every" in {
      pending // Too finicky on Travis
      type BD = (Boolean, FiniteDuration)
      val durationSinceLastTrue: Pipe[Pure,BD,BD] = {
        def go(lastTrue: FiniteDuration): Stream.Handle[Pure,BD] => Pull[Pure,BD,Unit] = h => {
          h.receive1 { (pair, tl) =>
            pair match {
              case (true , d) => Pull.output1((true , d - lastTrue)) >> go(d)(tl)
              case (false, d) => Pull.output1((false, d - lastTrue)) >> go(lastTrue)(tl)
            }
          }
        }
        _ pull go(0.seconds)
      }

      val delay = 20.millis
      val draws = (600.millis / delay) min 50 // don't take forever

      val durationsSinceSpike = time.every[Task](delay).
        map(d => (d, System.nanoTime.nanos)).
        take(draws.toInt).
        through(durationSinceLastTrue)

      durationsSinceSpike.runLog.unsafeRunAsyncFuture().map { result =>
        val (head :: tail) = result.toList
        withClue("every always emits true first") { assert(head._1) }
        withClue("true means the delay has passed: " + tail) { assert(tail.filter(_._1).map(_._2).forall { _ >= delay }) }
        withClue("false means the delay has not passed: " + tail) { assert(tail.filterNot(_._1).map(_._2).forall { _ <= delay }) }
      }
    }
  }
}
