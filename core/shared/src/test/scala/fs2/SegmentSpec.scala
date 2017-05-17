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
      s.unconsChunks match {
        case Right((hds, tl)) => go(acc ++ hds, tl)
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

    "dropWhile" in {
      forAll { (s: Segment[Int,Unit], f: Int => Boolean) =>
        s.dropWhile(f).toVector shouldBe s.toVector.dropWhile(f)
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
        val (hd, cnt, tail) = s.splitAt(n)
        Segment.catenated(hd).getOrElse(Segment.empty).toChunk shouldBe s.toChunk.take(n)
        cnt shouldBe s.toVector.take(n).size
        if (n >= s.toVector.size && n > 0)
          tail shouldBe Left(())
        else
          tail.map(_.toVector).getOrElse(Vector.empty) shouldBe s.toVector.drop(n)
      }
    }

    "splitWhile" in {
      forAll { (s: Segment[Int,Unit], f: Int => Boolean) =>
        val (segments, unfinished, tail) = s.splitWhile(f)
        Segment.catenated(segments).getOrElse(Segment.empty).toVector shouldBe s.toVector.takeWhile(f)
        unfinished shouldBe (s.toVector.takeWhile(f).size == s.toVector.size)
        val remainder = s.toVector.dropWhile(f)
        if (remainder.isEmpty) tail should (be(Left(())) or be(Right(Segment.empty)))
        else tail.map(_.toVector).getOrElse(Vector.empty) shouldBe remainder
      }
    }

    "splitWhile (2)" in {
      forAll { (s: Segment[Int,Unit], f: Int => Boolean) =>
        val (segments, unfinished, tail) = s.splitWhile(f, emitFailure = true)
        val svec = s.toVector
        Segment.catenated(segments).getOrElse(Segment.empty).toVector shouldBe (svec.takeWhile(f) ++ svec.dropWhile(f).headOption)
        unfinished shouldBe (svec.takeWhile(f).size == svec.size)
        val remainder = svec.dropWhile(f).drop(1)
        if (remainder.isEmpty) tail should (be(Left(())) or be(Right(Segment.empty)))
        else tail.map(_.toVector).getOrElse(Vector.empty) shouldBe remainder
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
      forAll { (xs: Segment[Int,Unit], ys: Segment[Int,Unit]) =>
        val xsv = xs.toVector
        val ysv = ys.toVector
        val f: (Int,Int) => (Int,Int) = (_,_)
        val (segments, leftover) = unconsAll(xs.zipWith(ys)(f))
        segments.toVector.flatMap(_.toVector) shouldBe xsv.zip(ysv).map { case (x,y) => f(x,y) }
        leftover match {
          case Left((_,leftoverYs)) => withClue("leftover ys")(leftoverYs.toVector shouldBe ysv.drop(xsv.size))
          case Right((_,leftoverXs)) => withClue("leftover xs")(leftoverXs.toVector shouldBe xsv.drop(ysv.size))
        }
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
