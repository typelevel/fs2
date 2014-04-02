package scalaz.stream

import scalaz.scalacheck.ScalazProperties.monad
import scalaz.Apply
import scalaz.std.anyVal._
import org.scalacheck.Properties
import ReceiveY._

import TestInstances._

object ReceiveYSpec extends Properties("These") {
  property("monad laws") = monad.laws[({type f[y] = ReceiveY[Int, y]})#f]
}
