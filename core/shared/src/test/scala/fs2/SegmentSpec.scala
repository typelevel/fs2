package fs2

import org.scalacheck.{ Arbitrary, Gen }
import Arbitrary.arbitrary

class SegmentSpec extends Fs2Spec {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 1000, workers = 4)

  def genSegment[O](genO: Gen[O]): Gen[Segment[O,Unit]] = Gen.oneOf(
    Gen.const(()).map(Segment.pure(_)),
    genO.map(Segment.singleton),
    Gen.listOf(genO).map(Segment.seq(_)),
    Gen.chooseNum(0, 3).flatMap(n => Gen.listOfN(n, Gen.listOf(genO)).flatMap { oss => Segment.unfoldChunk(0)(i => if (i < oss.size) Some(Chunk.seq(oss(i)) -> (i + 1)) else None) }),
    Gen.listOf(genO).map(_.foldLeft(Segment.empty[O])((acc, o) => acc ++ Segment.singleton(o))),
    Gen.delay(for { lhs <- genSegment(genO); rhs <- genSegment(genO) } yield lhs ++ rhs),
    Gen.delay(for { seg <- genSegment(genO); c <- Gen.listOf(genO).map(Chunk.seq) } yield seg.prepend(c))
  )

  implicit def arbSegment[O: Arbitrary]: Arbitrary[Segment[O,Unit]] =
    Arbitrary(genSegment(arbitrary[O]))

  def unconsAll[O,R](s: Segment[O,R]): (Catenable[Chunk[O]],R) = {
    def go(acc: Catenable[Chunk[O]], s: Segment[O,R]): (Catenable[Chunk[O]],R) =
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
        s.drop(n).fold(_ => Segment.empty, identity).toVector shouldBe s.toVector.drop(n)
      }
    }

    "dropWhile" in {
      forAll { (s: Segment[Int,Unit], f: Int => Boolean) =>
        s.dropWhile(f).fold(_ => Vector.empty, _.toVector) shouldBe s.toVector.dropWhile(f)
      }
    }

    "dropWhile (2)" in {
      forAll { (s: Segment[Int,Unit], f: Int => Boolean) =>
        val svec = s.toVector
        val svecD = if (svec.isEmpty) svec else svec.dropWhile(f).drop(1)
        val dropping = svec.isEmpty || (svecD.isEmpty && f(svec.last))
        s.dropWhile(f, true).map(_.toVector) shouldBe (if (dropping) Left(()) else Right(svecD))
      }
    }

    "flatMap" in {
      forAll { (s: Segment[Int,Unit], f: Int => Segment[Int,Unit]) =>
        s.flatMap(f).toVector shouldBe s.toVector.flatMap(i => f(i).toVector)
      }
    }

    "flatMap (2)" in {
      forAll { (s: Segment[Int,Unit], f: Int => Segment[Int,Unit], n: Int) =>
        val s2 = s.flatMap(f).take(n).drain.run.toOption
        s2.foreach { _.toVector shouldBe s.toVector.flatMap(i => f(i).toVector).drop(n) }
      }
    }

    "flatMapAccumulate" in {
      forAll { (s: Segment[Int,Unit], init: Double, f: (Double,Int) => (Double,Int)) =>
        val s1 = s.flatMapAccumulate(init) { (s,i) => val (s2,o) = f(s,i); Segment.singleton(o).asResult(s2) }
        val s2 = s.mapAccumulate(init)(f)
        s1.toVector shouldBe s2.toVector
        s1.drain.run shouldBe s2.drain.run
      }
    }

    "flatMapResult" in {
      forAll { (s: Segment[Int,Unit], r: Int, f: Int => Segment[Int, Unit]) =>
        s.asResult(r).flatMapResult(f).toVector shouldBe (s ++ f(r)).toVector
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
        val result = s.splitAt(n)
        val v = s.toVector
        if (n == 0 || n < v.size) {
          val Right((segments, rest)) = result
          segments.toVector.flatMap(_.toVector) shouldBe v.take(n)
          rest.toVector shouldBe v.drop(n)
        } else {
          val Left((_, segments, rem)) = result
          segments.toVector.flatMap(_.toVector) shouldBe v.take(n)
          rem shouldBe (n - v.size)
        }
      }
    }

    "splitWhile" in {
      forAll { (s: Segment[Int,Unit], f: Int => Boolean) =>
        val (segments, unfinished, tail) = s.splitWhile(f) match {
          case Left((_,segments)) => (segments, true, Segment.empty)
          case Right((segments,tail)) => (segments, false, tail)
        }
        Segment.catenated(segments).toVector shouldBe s.toVector.takeWhile(f)
        unfinished shouldBe (s.toVector.takeWhile(f).size == s.toVector.size)
        val remainder = s.toVector.dropWhile(f)
        if (remainder.isEmpty) tail shouldBe Segment.empty
        else tail.toVector shouldBe remainder
      }
    }

    "splitWhile (2)" in {
      forAll { (s: Segment[Int,Unit], f: Int => Boolean) =>
        val (segments, unfinished, tail) = s.splitWhile(f, emitFailure = true) match {
          case Left((_,segments)) => (segments, true, Segment.empty)
          case Right((segments,tail)) => (segments, false, tail)
        }
        val svec = s.toVector
        Segment.catenated(segments).toVector shouldBe (svec.takeWhile(f) ++ svec.dropWhile(f).headOption)
        unfinished shouldBe (svec.takeWhile(f).size == svec.size)
        val remainder = svec.dropWhile(f).drop(1)
        if (remainder.isEmpty) tail shouldBe Segment.empty
        else tail.toVector shouldBe remainder
      }
    }

    "splitWhile (3)" in {
      val (prefix, suffix) = Segment.seq(List.range(1,10)).map(_ - 1).splitWhile(_ < 5).toOption.get
      Segment.catenated(prefix).toVector shouldBe Vector(0, 1, 2, 3, 4)
      suffix.toVector shouldBe Vector(5, 6, 7, 8)
    }

    "sum" in {
      forAll { (s: Segment[Int,Unit]) =>
        s.sum.run shouldBe s.toVector.sum
      }
    }

    "take" in {
      forAll { (s: Segment[Int,Unit], n: Int) =>
        val v = s.toVector
        s.take(n).toVector shouldBe v.take(n)
        if (n > 0 && n >= v.size) {
          s.take(n).drain.run shouldBe Left(((), n - v.size))
        } else {
          s.take(n).drain.run shouldBe Right(Segment.vector(v.drop(n)))
        }
      }
    }

    "takeWhile" in {
      forAll { (s: Segment[Int,Unit], f: Int => Boolean) =>
        s.takeWhile(f).toVector shouldBe s.toVector.takeWhile(f)
      }
    }

    "takeWhile (2)" in {
      forAll { (s: Segment[Int,Unit], f: Int => Boolean) =>
        s.takeWhile(f, true).toVector shouldBe (s.toVector.takeWhile(f) ++ s.toVector.dropWhile(f).headOption)
      }
    }

    "unconsChunk" in {
      forAll { (xss: List[List[Int]]) =>
        val seg = xss.foldRight(Segment.empty[Int])((xs, acc) => Chunk.array(xs.toArray) ++ acc)
        // Consecutive empty chunks are collapsed to a single empty chunk
        unconsAll(seg)._1.toList.map(_.toVector.toList).filter(_.nonEmpty) shouldBe xss.filter(_.nonEmpty)
      }
    }

    "uncons1 performance" - {
      val Ns = List(2,3,100,200,400,800,1600,3200,6400,12800,25600,51200,102400)
      Ns.foreach { N => N.toString in {
        val s = Segment.from(0).take(N).voidResult
        def go(s: Segment[Long,Unit]): Unit = s.uncons1 match {
          case Right((hd,tl)) => go(tl)
          case Left(_) => ()
        }
        go(s)
      }}
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
      s.sum.run shouldBe (0 until N).sum
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
