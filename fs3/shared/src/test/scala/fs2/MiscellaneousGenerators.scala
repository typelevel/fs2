package fs2

import org.scalatest.prop._
import CommonGenerators._

trait MiscellaneousGenerators {

  implicit val throwableGenerator: Generator[Throwable] =
    specificValue(new Err)
}
