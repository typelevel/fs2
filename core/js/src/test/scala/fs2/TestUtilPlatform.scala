package fs2

import scala.concurrent.ExecutionContext
import cats.effect.IO

trait TestUtilPlatform {
  implicit val executionContext: ExecutionContext =
    ExecutionContext.Implicits.global
}
