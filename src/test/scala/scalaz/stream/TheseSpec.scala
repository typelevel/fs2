package scalaz.stream

import scalaz.scalacheck.ScalazProperties.monad
import scalaz.Apply
import scalaz.std.anyVal._
import org.scalacheck.{Arbitrary, Gen, Properties}
import Arbitrary.arbitrary
import These._

object TheseSpec extends Properties("These") {
  implicit def theseArb[A: Arbitrary, B: Arbitrary]: Arbitrary[These[A, B]] =
    Arbitrary(Gen.oneOf(
      arbitrary[A].map2(arbitrary[B])(Both(_,_)),
      arbitrary[A].map(This(_)),
      arbitrary[B].map(That(_))
    ))

  property("monad laws") = monad.laws[({type f[y] = These[Int, y]})#f]
}
