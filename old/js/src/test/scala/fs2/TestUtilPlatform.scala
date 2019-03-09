package fs2

import scala.concurrent.ExecutionContext

trait TestUtilPlatform {
  implicit val executionContext: ExecutionContext =
    ExecutionContext.Implicits.global
  def isJVM: Boolean = false
}
