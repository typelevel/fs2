package fs2
package time

import scala.concurrent.duration._
import org.scalatest.Succeeded

class TimeSpec extends AsyncFs2Spec {

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
      val strategy = implicitly[Strategy]
      val s = time.awakeEvery[Task](1.milli).evalMap { i => Task.async[Unit](cb => strategy(cb(Right(())))) }.take(200)
      runLogF { concurrent.join(5)(Stream(s, s, s, s, s)) }.map { _ => Succeeded }
    }

    "duration" in {
      val delay = 200 millis

      val blockingSleep = Task delay {
        Thread.sleep(delay.toMillis)
      }

      val emitAndSleep = Stream.emit(()) ++ Stream.eval(blockingSleep)
      val t = emitAndSleep zip time.duration[Task] drop 1 map { _._2 } runLog

      t.unsafeRunAsyncFuture() collect {
        case Vector(d) => assert(d >= delay)
      }
    }

    "every" in {
      pending // Too finicky on Travis
      type BD = (Boolean, FiniteDuration)
      val durationSinceLastTrue: Pipe[Pure,BD,BD] = {
        def go(lastTrue: FiniteDuration): Handle[Pure,BD] => Pull[Pure,BD,Unit] = h => {
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

    "sleep" in {
      val delay = 200 millis

      // force a sync up in duration, then measure how long sleep takes
      val emitAndSleep = Stream.emit(()) ++ time.sleep[Task](delay)
      val t = emitAndSleep zip time.duration[Task] drop 1 map { _._2 } runLog

      t.unsafeRunAsyncFuture() collect {
        case Vector(d) => assert(d >= delay)
      }
    }
  }
}
