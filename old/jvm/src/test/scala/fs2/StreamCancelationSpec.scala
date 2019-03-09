package fs2

import scala.concurrent.Future
import scala.concurrent.duration._
import cats.effect.IO
import cats.implicits._
import fs2.concurrent.Queue

class StreamCancelationSpec extends AsyncFs2Spec {
  def startAndCancelSoonAfter[A](fa: IO[A]): IO[Unit] =
    fa.start.flatMap(fiber => timerIO.sleep(1000.milliseconds) >> fiber.cancel)

  def testCancelation[A](s: Stream[IO, A]): Future[Unit] =
    startAndCancelSoonAfter(s.compile.drain).unsafeToFuture

  "cancelation of compiled streams" - {
    "constant" in testCancelation(Stream.constant(1))
    "bracketed stream" in testCancelation(
      Stream.bracket(IO.unit)(_ => IO.unit).flatMap(_ => Stream.constant(1)))
    "concurrently" in testCancelation {
      val s = Stream.constant(1).covary[IO]
      s.concurrently(s)
    }
    "merge" in testCancelation {
      val s = Stream.constant(1).covary[IO]
      s.merge(s)
    }
    "parJoin" in testCancelation {
      val s = Stream.constant(1).covary[IO]
      Stream(s, s).parJoin(2)
    }
    "#1236" in testCancelation {
      Stream
        .eval(Queue.bounded[IO, Int](1))
        .flatMap { q =>
          Stream(
            Stream
              .unfold(0)(i => (i + 1, i + 1).some)
              .flatMap { i =>
                Stream.sleep_(50.milliseconds) ++ Stream.emit(i)
              }
              .through(q.enqueue),
            q.dequeue.showLinesStdOut
          ).parJoin(2)
        }
    }
  }
}
