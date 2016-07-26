package fs2

trait TestUtilPlatform {
  implicit val S: Strategy = Strategy.default
  implicit val scheduler: Scheduler = Scheduler.default
}
