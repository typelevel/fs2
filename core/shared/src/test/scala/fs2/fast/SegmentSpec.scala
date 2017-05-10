package fs2.fast

import org.scalacheck.{ Arbitrary, Gen }
import Arbitrary.arbitrary
import org.scalatest.{ FreeSpec, Matchers }
import org.scalatest.prop.GeneratorDrivenPropertyChecks

import fs2.util.Catenable

class SegmentSpec extends FreeSpec with Matchers with GeneratorDrivenPropertyChecks {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 2000, workers = 4)

  def genSegment[O](genO: Gen[O]): Gen[Segment[O,Unit]] = Gen.oneOf(
    Gen.const(()).map(Segment.pure(_)),
    genO.map(Segment.singleton),
    Gen.listOf(genO).map(Segment.seq(_)),
    Gen.delay(for { lhs <- genSegment(genO); rhs <- genSegment(genO) } yield lhs ++ rhs),
    Gen.delay(for { seg <- genSegment(genO); c <- Gen.listOf(genO).map(Chunk.seq) } yield seg.push(c))
  )

  implicit def arbSegment[O: Arbitrary]: Arbitrary[Segment[O,Unit]] =
    Arbitrary(genSegment(arbitrary[O]))

  "Segment" - {

    "++" in {
      forAll { (xs: List[Int], ys: List[Int]) =>
        val appended = Segment.seq(xs) ++ Segment.seq(ys)
        appended.toVector shouldBe (xs.toVector ++ ys.toVector)
      }
    }

    "toChunk" in {
      forAll { (xs: List[Int]) =>
        Segment.seq(xs).toChunk.toVector shouldBe xs.toVector
      }
    }

    "drop" in {
      forAll { (s: Segment[Int,Unit], n: Int) =>
        s.drop(n).toChunk shouldBe s.toChunk.drop(n)
      }
    }

    "fold" in {
      forAll { (s: Segment[Int,Unit], init: Int, f: (Int, Int) => Int) =>
        s.fold(init)(f).run shouldBe s.toChunk.toVector.foldLeft(init)(f)
      }
    }

    "map" in {
      forAll { (s: Segment[Int,Unit], f: Int => Double) =>
        s.map(f).toChunk shouldBe s.toChunk.map(f)
      }
    }

    "scan" in {
      forAll { (s: Segment[Int,Unit], init: Int, f: (Int, Int) => Int) =>
        s.scan(init)(f).toChunk.toVector shouldBe s.toChunk.toVector.scanLeft(init)(f)
      }
    }

    "splitAt" in {
      forAll { (s: Segment[Int,Unit], n: Int) =>
        val (hd, tl) = s.splitAt(n)
        hd shouldBe s.toChunk.take(n)
      }
    }

    "sum" in {
      forAll { (s: Segment[Int,Unit]) =>
        s.sum(0).run shouldBe s.toChunk.toVector.sum
      }
    }

    "take" in {
      forAll { (s: Segment[Int,Unit], n: Int) =>
        s.take(n).toChunk shouldBe s.toChunk.take(n)
      }
    }

    "unconsChunk" in {
      forAll { (xss: List[List[Int]]) =>
        val seg = xss.foldRight(Segment.empty[Int])((xs, acc) => Chunk.array(xs.toArray) ++ acc)
        def unconsAll(acc: Catenable[Chunk[Int]], s: Segment[Int,Unit]): Catenable[Chunk[Int]] =
          s.unconsChunk match {
            case Right((hd, tl)) => unconsAll(acc :+ hd, tl)
            case Left(()) => acc
          }
        unconsAll(Catenable.empty, seg).toList.map(_.toVector.toList) shouldBe xss.filter(_.nonEmpty)
      }
    }

    "staging stack safety" in {
      val N = 100000
      val s = (0 until N).foldLeft(Segment.singleton(0))((s,i) => s map (_ + i))
      s.sum(0).run shouldBe (0 until N).sum
    }

    val Ns = List(2,3,100,200,400,800,1600,3200,6400,12800,25600,51200,102400)
    "uncons1 is O(1)" - { Ns.foreach { N =>
      N.toString in {
        def go[O,R](s: Segment[O,R]): R =
          s.uncons1 match {
            case Left(r) => r
            case Right((o, s)) => go(s)
          }
        go(Chunk.indexedSeq(0 until N))
      }
    }}
  }
}
