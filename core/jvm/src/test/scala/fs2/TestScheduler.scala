package fs2

import cats.effect.IO

object TestScheduler {
  implicit val scheduler: Scheduler = Scheduler.allocate[IO](2).map(_._1).unsafeRunSync
}
