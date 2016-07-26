package fs2

import scala.concurrent.duration._

trait TestUtilPlatform {

  implicit val S: Strategy = TestStrategy.S
  implicit val scheduler: Scheduler = TestStrategy.scheduler

  def runLog[A](s: Stream[Task,A], timeout: FiniteDuration = 3.minutes): Vector[A] = s.runLog.unsafeRunFor(timeout)

  def throws[A](err: Throwable)(s: Stream[Task,A]): Boolean =
    s.runLog.unsafeAttemptRun() match {
      case Left(e) if e == err => true
      case _ => false
    }
}
