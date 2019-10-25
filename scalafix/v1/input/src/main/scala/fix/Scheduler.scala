/*
rule = v1
 */
package fix

import cats.effect._
import fs2._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

abstract class SchedulerTimer[F[_]: Effect] {

  val scheduler: Scheduler
  val duration = 1.second
  scheduler.effect.sleep[F](duration)
  scheduler.sleep[F](duration)
  scheduler.sleep_[F](duration)
  scheduler.awakeEvery[F](duration)
  scheduler.retry(Effect[F].unit, duration, _ => duration, 1)
  Stream.eval(Effect[F].unit).through(scheduler.debounce(duration))
  scheduler.effect.delayCancellable(Effect[F].unit, duration)
  scheduler.delay(Stream.eval(Effect[F].unit), duration)
}
