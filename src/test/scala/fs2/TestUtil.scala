package fs2

import fs2.util.Task
import org.scalacheck.{Arbitrary, Gen}

object TestUtil {

  def run[A](s: Stream[Task,A]): Vector[A] = s.runLog.run.run

  implicit class EqualsOp[A](s: Stream[Task,A]) {
    def ===(v: Vector[A]) = run(s) == v
    def ==?(v: Vector[A]) = {
      val l = run(s)
      val r = v
      l == r || { println("left: " + l); println("right: " + r); false }
    }
  }

  val nonEmptyNestedVectorGen = for {
    sizeOuter <- Gen.choose(1, 10)
    sizeInner <- Gen.choose(1, 10)
    inner = Gen.listOfN(sizeInner, Arbitrary.arbInt.arbitrary).map(_.toVector)
    outer <- Gen.listOfN(sizeOuter, inner).map(_.toVector)
  } yield outer
}
