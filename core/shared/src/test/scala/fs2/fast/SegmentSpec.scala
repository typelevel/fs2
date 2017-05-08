package fs2.fast

import org.scalacheck.{ Arbitrary, Gen }
import Arbitrary.arbitrary
import org.scalatest.{ FreeSpec, Matchers }
import org.scalatest.prop.GeneratorDrivenPropertyChecks

import fs2.Chunk
import fs2.util.Catenable

class SegmentSpec extends FreeSpec with Matchers with GeneratorDrivenPropertyChecks {

  def genSegment[O](genO: Gen[O]): Gen[Segment[O,Unit]] = Gen.oneOf(
    Gen.const(()).map(Segment.pure(_)),
    genO.map(Segment.single),
    Gen.listOf(genO).map(Segment.seq(_)),
    Gen.delay(for { lhs <- genSegment(genO); rhs <- genSegment(genO) } yield lhs ++ rhs),
    Gen.delay(for { seg <- genSegment(genO); c <- Gen.listOf(genO).map(Chunk.seq) } yield seg.push(c))
  )

  implicit def arbSegment[O: Arbitrary]: Arbitrary[Segment[O,Unit]] =
    Arbitrary(genSegment(arbitrary[O]))

  "Segment" - {

    "toChunk" in {
      forAll { (xs: List[Int]) =>
        Segment.seq(xs).toChunk.toList shouldBe xs
      }
    }

    "fold" in {
      forAll { (s: Segment[Int,Unit], init: Int, f: (Int, Int) => Int) =>
        s.fold(init)(f).run shouldBe s.toChunk.toList.foldLeft(init)(f)
      }
    }

    "map" in {
      forAll { (s: Segment[Int,Unit], f: Int => Double) =>
        s.map(f).toChunk shouldBe s.toChunk.map(f)
      }
    }

    "scan" in {
      forAll { (s: Segment[Int,Unit], init: Int, f: (Int, Int) => Int) =>
        s.scan(init)(f).toChunk.toList shouldBe s.toChunk.toList.scanLeft(init)(f)
      }
    }

    "sum" in {
      forAll { (s: Segment[Int,Unit]) =>
        s.sum(0).run shouldBe s.toChunk.toList.sum
      }
    }

    "take" in {
      forAll { (s: Segment[Int,Unit], n: Int) =>
        s.take(n).toChunk shouldBe s.toChunk.take(n)
      }
    }

    "uncons" in {
      forAll { (xss: List[List[Int]]) =>
        val seg = xss.foldRight(Segment.empty[Int])((xs, acc) => Segment.seq(xs) ++ acc)
        def unconsAll(acc: Catenable[Chunk[Int]], s: Segment[Int,Unit]): Catenable[Chunk[Int]] = s.uncons match {
          case Right((hd, tl)) => unconsAll(acc :+ hd, tl)
          case Left(()) => acc
        }
        unconsAll(Catenable.empty, seg).toList.map(_.toList) shouldBe xss
      }
    }
  }
}
