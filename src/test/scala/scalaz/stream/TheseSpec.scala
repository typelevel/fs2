package scalaz.stream

import org.specs2.scalaz.Spec
import scalaz.scalacheck.ScalazProperties.monad
import scalaz.scalacheck.ScalaCheckBinding._
import scalaz.Apply
import scalaz.std.anyVal._
import org.scalacheck.{Arbitrary, Gen}
import Arbitrary.arbitrary
import These._

class TheseSpec extends Spec {
  implicit def theseArb[A: Arbitrary, B: Arbitrary]: Arbitrary[These[A, B]] =
    Arbitrary(Gen.oneOf(
      arbitrary[A].map(This(_)),
      arbitrary[B].map(That(_)),
      Apply[Gen].map2(arbitrary[A], arbitrary[B])(Both(_, _))
    ))

  checkAll(monad.laws[({type f[y] = These[Int, y]})#f])
}
