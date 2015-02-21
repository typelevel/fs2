package scalaz.stream

import org.scalacheck.Prop._
import org.scalacheck.{Gen, Properties}
import scala.concurrent.duration._
import scalaz.concurrent.Strategy

import Process._
import time._

object TimeSpec extends Properties("time") {

  implicit val S = Strategy.DefaultStrategy
  implicit val scheduler = scalaz.stream.DefaultScheduler

  property("awakeEvery") = secure {
    time.awakeEvery(100 millis).map(_.toMillis/100).take(5).runLog.run == Vector(1,2,3,4,5)
  }

  property("duration") = secure {
    val firstValueDiscrepancy = time.duration.once.runLast
    val reasonableErrorInMillis = 200
    val reasonableErrorInNanos = reasonableErrorInMillis * 1000000
    def p = firstValueDiscrepancy.run.get.toNanos < reasonableErrorInNanos

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
        def go(lastTrue: Duration): Process1[BD,BD] = {
          await1 flatMap { pair:(Boolean, Duration) => pair match {
            case (true , d) => emit((true , d - lastTrue)) ++ go(d)
            case (false, d) => emit((false, d - lastTrue)) ++ go(lastTrue)
          } }
        }
        go(0.seconds)
      }

      val draws = (600.millis / delay) min 10 // don't take forever

      val durationsSinceSpike = time.every(delay).
        tee(time.duration)(tee zipWith {(a,b) => (a,b)}).
        take(draws.toInt) |>
        durationSinceLastTrue

      val result = durationsSinceSpike.runLog.run.toList
      val (head :: tail) = result

      head._1 :| "every always emits true first" &&
        tail.filter   (_._1).map(_._2).forall { _ >= delay } :| "true means the delay has passed" &&
        tail.filterNot(_._1).map(_._2).forall { _ <= delay } :| "false means the delay has not passed"
    }
}
