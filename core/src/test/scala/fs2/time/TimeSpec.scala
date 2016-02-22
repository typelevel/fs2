package fs2
package time

import org.scalacheck.Prop._
import org.scalacheck.{Gen, Properties}
import scala.concurrent.duration._

import fs2.util.Task
import Stream._

class TimeSpec extends Properties("time") {

  implicit val scheduler = java.util.concurrent.Executors.newScheduledThreadPool(2)
  implicit val S = Strategy.fromExecutor(scheduler)

  property("awakeEvery") = protect {
    time.awakeEvery(100.millis).map(_.toMillis/100).take(5).runLog.run.run == Vector(1,2,3,4,5)
  }

  property("duration") = protect {
    val firstValueDiscrepancy = time.duration.take(1).runLog.run.run.last
    val reasonableErrorInMillis = 200
    val reasonableErrorInNanos = reasonableErrorInMillis * 1000000
    def p = firstValueDiscrepancy.toNanos < reasonableErrorInNanos

    val r1 = p :| "first duration is near zero on first run"
    Thread.sleep(reasonableErrorInMillis)
    val r2 = p :| "first duration is near zero on second run"

    r1 && r2
  }

  val smallDelay = Gen.choose(10, 300) map {_.millis}

  property("every") =
    forAll(smallDelay) { delay: Duration =>
      type BD = (Boolean, Duration)
      val durationSinceLastTrue: Process1[BD, BD] = {
        def go(lastTrue: Duration): Handle[Pure,BD] => Pull[Pure,BD,Handle[Pure,BD]] = h => {
          h.receive1 {
            case pair #: tl =>
              pair match {
                case (true , d) => Pull.output1((true , d - lastTrue)) >> go(d)(tl)
                case (false, d) => Pull.output1((false, d - lastTrue)) >> go(lastTrue)(tl)
              }
          }
        }
        _ pull go(0.seconds)
      }

      val draws = (600.millis / delay) min 10 // don't take forever

      val durationsSinceSpike = time.every(delay).
        tee(time.duration)(tee zipWith {(a,b) => (a,b)}).
        take(draws.toInt) pipe
        durationSinceLastTrue

      val result = durationsSinceSpike.runLog.run.run.toList
      val (head :: tail) = result

      head._1 :| "every always emits true first" &&
        tail.filter   (_._1).map(_._2).forall { _ >= delay } :| "true means the delay has passed" &&
        tail.filterNot(_._1).map(_._2).forall { _ <= delay } :| "false means the delay has not passed"
    }
}

