package fs2

import fs2.util.{Sub1,Task}
import org.scalacheck.{Arbitrary, Gen}

object TestUtil {

  def run[A](s: Stream[Task,A]): Vector[A] = s.runLog.run.run

  implicit class EqualsOp[F[_],A](s: Stream[F,A])(implicit S: Sub1[F,Task]) {
    def ===(v: Vector[A]) = run(s.covary) == v
    def ==?(v: Vector[A]) = {
      val l = run(s.covary)
      val r = v
      l == r || { println("left: " + l); println("right: " + r); false }
    }
  }

  /** Newtype for generating test cases. */
  case class PureStream[+A](tag: String, stream: Stream[Nothing,A])

  object PureStream {
    def singleChunk[A](implicit A: Arbitrary[A]): Gen[PureStream[A]] = Gen.sized { size =>
      Gen.listOfN(size, A.arbitrary).map(as => PureStream("single chunk", Stream.emits(as)))
    }
    //def unchunked[A](implicit A: Arbitrary[A]): Gen[PureStream[A]] = Gen.sized { size =>
    //  Gen.listOfN(size, A.arbitrary).map(as => PureStream("unchunked", Stream.emits(as).unchunk))
    //}
  }

  val nonEmptyNestedVectorGen = for {
    sizeOuter <- Gen.choose(1, 10)
    sizeInner <- Gen.choose(1, 10)
    inner = Gen.listOfN(sizeInner, Arbitrary.arbInt.arbitrary).map(_.toVector)
    outer <- Gen.listOfN(sizeOuter, inner).map(_.toVector)
  } yield outer
}
