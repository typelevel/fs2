package fs2

import scala.concurrent.ExecutionContext

trait TestUtilPlatform {
  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val scheduler: Scheduler = Scheduler.default
}
