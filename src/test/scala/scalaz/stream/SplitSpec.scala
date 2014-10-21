package scalaz.stream

import org.scalacheck.Properties
import org.scalacheck.Prop._
import scalaz.std.anyVal._

object SplitSpec extends Properties("split") {
  import split._

  val example = Process(1, 2, 3, 4, 5)

  property("splitOn") = secure {
    example.pipe(splitOn(Vector(3, 4))).toList == List(Vector(1, 2), Vector(5))
  }

  property("splitOneOf") = secure {
    example.pipe(splitOneOf(Vector(2, 5))).toList == List(Vector(1), Vector(3, 4), Vector())
  }
}
