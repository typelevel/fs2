package fs2

import cats.effect.IO
import cats.implicits._
import scala.concurrent.duration._

class SchedulerSpec extends AsyncFs2Spec {

  "Scheduler" - {

    "sleep" in {
      val delay = 200 millis

      // force a sync up in duration, then measure how long sleep takes
      val emitAndSleep = Stream.emit(()) ++ mkScheduler.flatMap(_.sleep[IO](delay))
      val t = emitAndSleep zip Stream.duration[IO] drop 1 map { _._2 } runLog

      (IO.shift *> t).unsafeToFuture() collect {
        case Vector(d) => assert(d >= delay)
      }
    }

    "debounce" in {
      val delay = 200 milliseconds
      val t = mkScheduler.flatMap { scheduler =>
        val s1 = Stream(1, 2, 3) ++ scheduler.sleep[IO](delay * 2) ++ Stream() ++ Stream(4, 5) ++ scheduler.sleep[IO](delay / 2) ++ Stream(6)
        s1.through(scheduler.debounce(delay))
      }.runLog
      t.unsafeToFuture() map { r =>
        assert(r == Vector(3, 6))
      }
    }
  }
}
