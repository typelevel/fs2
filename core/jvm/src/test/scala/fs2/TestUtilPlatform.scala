package fs2

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.effect.IO

import java.util.concurrent.TimeoutException

trait TestUtilPlatform {

  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val scheduler: Scheduler = TestScheduler.scheduler

  val timeout: FiniteDuration

  def runLog[A](s: Stream[IO,A], timeout: FiniteDuration = timeout): Vector[A] =
    s.runLog.unsafeRunTimed(timeout).getOrElse(throw new TimeoutException("IO run timed out"))

  def throws[A](err: Throwable)(s: Stream[IO,A]): Boolean =
    s.runLog.attempt.unsafeRunSync() match {
      case Left(e) if e == err => true
      case _ => false
    }
}
