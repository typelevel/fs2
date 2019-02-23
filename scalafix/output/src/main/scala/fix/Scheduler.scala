package fix

import cats.effect._
import fs2._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

abstract class SchedulerTimer[F[_]: Effect] {

  val scheduler: Timer[F]
  val duration = 1.second
  scheduler.sleep(duration)
  Stream.sleep[F](duration)
  Stream.sleep_[F](duration)
  Stream.awakeEvery[F](duration)
  Stream.retry(Effect[F].unit, duration, _ => duration, 1)
  Stream.eval(Effect[F].unit).debounce(duration)
  Concurrent[F].race(Effect[F].unit, scheduler.sleep(duration))
  Stream.eval(Effect[F].unit).delayBy(duration)
}