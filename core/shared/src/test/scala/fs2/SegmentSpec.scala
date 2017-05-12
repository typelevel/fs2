package fs2

import org.scalacheck.{ Arbitrary, Gen }
import Arbitrary.arbitrary

class SegmentSpec extends Fs2Spec {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 2000, workers = 4)

  def genSegment[O](genO: Gen[O]): Gen[Segment[O,Unit]] = Gen.oneOf(
    Gen.const(()).map(Segment.pure(_)),
    genO.map(Segment.singleton),
    Gen.listOf(genO).map(Segment.seq(_)),
    Gen.delay(for { lhs <- genSegment(genO); rhs <- genSegment(genO) } yield lhs ++ rhs),
    Gen.delay(for { seg <- genSegment(genO); c <- Gen.listOf(genO).map(Chunk.seq) } yield seg.cons(c))
  )

  implicit def arbSegment[O: Arbitrary]: Arbitrary[Segment[O,Unit]] =
    Arbitrary(genSegment(arbitrary[O]))

  def unconsAll[O,R](s: Segment[O,R]): (Catenable[Chunk[O]],R) = {
    def go[O,R](acc: Catenable[Chunk[O]], s: Segment[O,R]): (Catenable[Chunk[O]],R) =
      s.unconsChunk match {
        case Right((hd, tl)) => go(acc :+ hd, tl)
        case Left(r) => (acc, r)
      }
    go(Catenable.empty, s)
  }

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

    "collect" in {
      forAll { (s: Segment[Int,Unit], f: Int => Double, defined: Int => Boolean) =>
        val pf: PartialFunction[Int,Double] = { case n if defined(n) => f(n) }
        s.collect(pf).toVector shouldBe s.toVector.collect(pf)
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

    "takeWhile" in {
      forAll { (s: Segment[Int,Unit], f: Int => Boolean) =>
        s.takeWhile(f).toVector shouldBe s.toVector.takeWhile(f)
      }
    }

    "unconsChunk" in {
      forAll { (xss: List[List[Int]]) =>
        val seg = xss.foldRight(Segment.empty[Int])((xs, acc) => Chunk.array(xs.toArray) ++ acc)
        unconsAll(seg)._1.toList.map(_.toVector.toList) shouldBe xss.filter(_.nonEmpty)
      }
    }

    "uncons1 on result of uncons always emits" in {
      forAll { (s: Segment[Int,Unit]) =>
        s.uncons match {
          case Left(()) => ()
          case Right((hd,tl)) => hd.uncons1.toOption.get
        }
      }
    }

    "zipWith" in {
      forAll { (xs: Vector[Int], ys: Vector[Double], f: (Int, Double) => Long) =>
        val (segments, leftover) = unconsAll(Segment.seq(xs).zipWith(Segment.seq(ys))(f))
        segments.toVector.flatMap(_.toVector) shouldBe xs.zip(ys).toVector.map { case (x,y) => f(x,y) }
        // val expectedResult: Either[Vector[Double],Vector[Int]] = {
        //   if (xs.size < ys.size) Left(ys.drop(xs.size))
        //   else Right(xs.drop(ys.size))
        // }
        leftover match {
          case Left((_,leftoverYs)) => withClue("leftover ys")(leftoverYs.toVector shouldBe ys.drop(xs.size))
          case Right((_,leftoverXs)) => withClue("leftover xs")(leftoverXs.toVector shouldBe xs.drop(ys.size))
        }
        // leftover.fold(l => Left(l._2.toVector), r => Right(r._2.toVector)) shouldBe expectedResult
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
