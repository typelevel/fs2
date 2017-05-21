package fs2

import cats.effect.IO
import scala.concurrent.duration._

class TimeSpec extends AsyncFs2Spec {

  "time" - {

    "duration" in {
      val delay = 200 millis

      val blockingSleep = IO { Thread.sleep(delay.toMillis) }

      val emitAndSleep = Stream.emit(()) ++ Stream.eval(blockingSleep)
      val t = emitAndSleep zip time.duration[IO] drop 1 map { _._2 } runLog

      t.shift.unsafeToFuture collect {
        case Vector(d) => assert(d.toMillis >= delay.toMillis - 5)
      }
    }

    "every" in {
      pending // Too finicky on Travis
      type BD = (Boolean, FiniteDuration)
      val durationSinceLastTrue: Pipe[Pure,BD,BD] = {
        def go(lastTrue: FiniteDuration, s: Stream[Pure,BD]): Pull[Pure,BD,Unit] = {
          s.pull.uncons1.flatMap {
            case None => Pull.done
            case Some((pair, tl)) =>
              pair match {
                case (true , d) => Pull.output1((true , d - lastTrue)) >> go(d,tl)
                case (false, d) => Pull.output1((false, d - lastTrue)) >> go(lastTrue,tl)
              }
          }
        }
        s => go(0.seconds, s).stream
      }

      val delay = 20.millis
      val draws = (600.millis / delay) min 50 // don't take forever

      val durationsSinceSpike = time.every[IO](delay).
        map(d => (d, System.nanoTime.nanos)).
        take(draws.toInt).
        through(durationSinceLastTrue)

      durationsSinceSpike.runLog.shift.unsafeToFuture().map { result =>
        val (head :: tail) = result.toList
        withClue("every always emits true first") { assert(head._1) }
        withClue("true means the delay has passed: " + tail) { assert(tail.filter(_._1).map(_._2).forall { _ >= delay }) }
        withClue("false means the delay has not passed: " + tail) { assert(tail.filterNot(_._1).map(_._2).forall { _ <= delay }) }
      }
    }

    "sleep" in {
      val delay = 200 millis

      // force a sync up in duration, then measure how long sleep takes
      val emitAndSleep = Stream.emit(()) ++ time.sleep[IO](delay)
      val t = emitAndSleep zip time.duration[IO] drop 1 map { _._2 } runLog

      t.shift.unsafeToFuture() collect {
        case Vector(d) => assert(d >= delay)
      }
    }

    "debounce" in {
      val delay = 200 milliseconds
      val s1 = Stream(1, 2, 3) ++ time.sleep[IO](delay * 2) ++ Stream() ++ Stream(4, 5) ++ time.sleep[IO](delay / 2) ++ Stream(6)
      val t = s1.debounce(delay) runLog

      t.unsafeToFuture() map { r =>
        assert(r == Vector(3, 6))
      }
    }
  }
}
