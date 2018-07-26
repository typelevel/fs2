package fs2

import cats.effect.IO
import cats.implicits._
import scala.concurrent.duration._
import org.scalatest.Succeeded

class StreamTimerSpec extends AsyncFs2Spec {

  "Stream's Timer based operations" - {

    "sleep" in {
      val delay = 200 millis

      // force a sync up in duration, then measure how long sleep takes
      val emitAndSleep = Stream.emit(()) ++ Stream.sleep[IO](delay)
      val t =
        emitAndSleep.zip(Stream.duration[IO]).drop(1).map(_._2).compile.toVector

      (IO.shift *> t).unsafeToFuture().collect {
        case Vector(d) => assert(d >= delay)
      }
    }

    "debounce" in {
      val delay = 200 milliseconds
      val t = (Stream(1, 2, 3) ++ Stream.sleep[IO](delay * 2) ++ Stream() ++ Stream(4, 5) ++ Stream
        .sleep[IO](delay / 2) ++ Stream(6)).debounce(delay).compile.toVector
      t.unsafeToFuture().map { r =>
        assert(r == Vector(3, 6))
      }
    }

    "awakeEvery" in {
      Stream
        .awakeEvery[IO](500.millis)
        .map(_.toMillis)
        .take(5)
        .compile
        .toVector
        .unsafeToFuture()
        .map { r =>
          r.sliding(2)
            .map { s =>
              (s.head, s.tail.head)
            }
            .map { case (prev, next) => next - prev }
            .foreach { delta =>
              delta shouldBe 500L +- 150
            }
          Succeeded
        }
    }

    "awakeEvery liveness" in {
      val s = Stream
        .awakeEvery[IO](1.milli)
        .evalMap { i =>
          IO.async[Unit](cb => executionContext.execute(() => cb(Right(()))))
        }
        .take(200)
      Stream(s, s, s, s, s).parJoin(5).compile.toVector.unsafeToFuture().map { _ =>
        Succeeded
      }
    }
  }
}
