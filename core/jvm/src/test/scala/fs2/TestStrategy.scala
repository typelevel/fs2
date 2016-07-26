package fs2

object TestStrategy {
  implicit val S: Strategy = Strategy.fromFixedDaemonPool(8)
  implicit val scheduler: Scheduler = Scheduler.fromFixedDaemonPool(2)
}
