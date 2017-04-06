package fs2

object TestScheduler {
  implicit val scheduler: Scheduler = Scheduler.fromFixedDaemonPool(2)
}
