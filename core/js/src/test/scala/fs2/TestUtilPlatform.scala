package fs2

import scala.concurrent.ExecutionContext
import cats.effect.IO

trait TestUtilPlatform {
  implicit val executionContext: ExecutionContext =
    ExecutionContext.Implicits.global
  val mkScheduler: Stream[IO, Scheduler] = Stream.emit(Scheduler.default)
}
