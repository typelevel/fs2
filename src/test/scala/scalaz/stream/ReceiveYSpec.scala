package scalaz.stream

import scalaz.scalacheck.ScalazProperties.monad
import scalaz.Apply
import scalaz.std.anyVal._
import org.scalacheck.{Arbitrary, Gen, Properties}
import Arbitrary.arbitrary
import ReceiveY._

object ReceiveYSpec extends Properties("These") {
  implicit def theseArb[A: Arbitrary, B: Arbitrary]: Arbitrary[ReceiveY[A, B]] =
    Arbitrary(Gen.oneOf(
      arbitrary[A].map(ReceiveL(_)),
      arbitrary[B].map(ReceiveR(_))
    ))

  property("monad laws") = monad.laws[({type f[y] = ReceiveY[Int, y]})#f]
}
