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
    Gen.delay(for { seg <- genSegment(genO); c <- Gen.listOf(genO).map(Segment.seq) } yield seg.prepend(c))
  )

  implicit def arbSegment[O: Arbitrary]: Arbitrary[Segment[O,Unit]] =
    Arbitrary(genSegment(arbitrary[O]))

  "Segment" - {

    "++" in {
      forAll { (xs: Vector[Int], ys: Vector[Int]) =>
        val appended = Segment.seq(xs) ++ Segment.seq(ys)
        appended.force.toVector shouldBe (xs ++ ys)
      }
    }

    "toChunk" in {
      forAll { (xs: List[Int]) =>
        Segment.seq(xs).force.toChunk shouldBe Chunk.seq(xs)
      }
    }

    "collect" in {
      forAll { (s: Segment[Int,Unit], f: Int => Double, defined: Int => Boolean) =>
        val pf: PartialFunction[Int,Double] = { case n if defined(n) => f(n) }
        s.collect(pf).force.toVector shouldBe s.force.toVector.collect(pf)
      }
    }

    "drop" in {
      forAll { (s: Segment[Int,Unit], n: Int) =>
        s.force.drop(n).fold(_ => Segment.empty, identity).force.toVector shouldBe s.force.toVector.drop(n)
      }
    }

    "dropWhile" in {
      forAll { (s: Segment[Int,Unit], f: Int => Boolean) =>
        s.force.dropWhile(f).fold(_ => Vector.empty, _.force.toVector) shouldBe s.force.toVector.dropWhile(f)
      }
    }

    "dropWhile (2)" in {
      forAll { (s: Segment[Int,Unit], f: Int => Boolean) =>
        val svec = s.force.toVector
        val svecD = if (svec.isEmpty) svec else svec.dropWhile(f).drop(1)
        val dropping = svec.isEmpty || (svecD.isEmpty && f(svec.last))
        s.force.dropWhile(f, true).map(_.force.toVector) shouldBe (if (dropping) Left(()) else Right(svecD))
      }
    }

    "flatMap" in {
      forAll { (s: Segment[Int,Unit], f: Int => Segment[Int,Unit]) =>
        s.flatMap(f).force.toVector shouldBe s.force.toVector.flatMap(i => f(i).force.toVector)
      }
    }

    "flatMap (2)" in {
      forAll { (s: Segment[Int,Unit], f: Int => Segment[Int,Unit], n: Int) =>
        val s2 = s.flatMap(f).take(n).drain.force.run.toOption
        s2.foreach { _.force.toVector shouldBe s.force.toVector.flatMap(i => f(i).force.toVector).drop(n) }
      }
    }

    "flatMapAccumulate" in {
      forAll { (s: Segment[Int,Unit], init: Double, f: (Double,Int) => (Double,Int)) =>
        val s1 = s.flatMapAccumulate(init) { (s,i) => val (s2,o) = f(s,i); Segment.singleton(o).asResult(s2) }
        val s2 = s.mapAccumulate(init)(f)
        s1.force.toVector shouldBe s2.force.toVector
        s1.drain.force.run shouldBe s2.drain.force.run
      }
    }

    "flatMapResult" in {
      forAll { (s: Segment[Int,Unit], r: Int, f: Int => Segment[Int, Unit]) =>
        s.asResult(r).flatMapResult(f).force.toVector shouldBe (s ++ f(r)).force.toVector
      }
    }

    "fold" in {
      forAll { (s: Segment[Int,Unit], init: Int, f: (Int, Int) => Int) =>
        s.fold(init)(f).mapResult(_._2).force.run shouldBe s.force.toVector.foldLeft(init)(f)
      }
    }

    "last" in {
      forAll { (s: Segment[Int,Unit]) =>
        val (out, (r, l)) = s.last.force.unconsAll
        val flattenedOutput = out.toList.flatMap(_.toList)
        val sList = s.force.toList
        flattenedOutput shouldBe (if (sList.isEmpty) Nil else sList.init)
        l shouldBe sList.lastOption
      }
    }

    "map" in {
      forAll { (s: Segment[Int,Unit], f: Int => Double) =>
        s.map(f).force.toChunk shouldBe s.force.toChunk.map(f)
      }
    }

    "scan" in {
      forAll { (s: Segment[Int,Unit], init: Int, f: (Int, Int) => Int) =>
        s.scan(init)(f).force.toVector shouldBe s.force.toVector.scanLeft(init)(f)
      }
    }

    "splitAt" in {
      forAll { (s: Segment[Int,Unit], n: Int) =>
        val result = s.force.splitAt(n)
        val v = s.force.toVector
        if (n == v.size) {
          result match {
            case Left((_, chunks, rem)) =>
              chunks.toVector.flatMap(_.toVector) shouldBe v
              rem shouldBe 0
            case Right((chunks, rest)) =>
              chunks.toVector.flatMap(_.toVector) shouldBe v
              rest.force.toVector shouldBe Vector.empty
          }
        } else if (n == 0 || n < v.size) {
          val Right((chunks, rest)) = result
          chunks.toVector.flatMap(_.toVector) shouldBe v.take(n)
          rest.force.toVector shouldBe v.drop(n)
        } else {
          val Left((_, chunks, rem)) = result
          chunks.toVector.flatMap(_.toVector) shouldBe v.take(n)
          rem shouldBe (n - v.size)
        }
      }
    }

    "splitAt eagerly exits" in {
      val Right((chunks, rest)) = Segment.from(0L).filter(_ < 2L).force.splitAt(2)
      chunks.toVector.flatMap(_.toVector) shouldBe Vector(0L, 1L)
    }

    "splitWhile" in {
      forAll { (s: Segment[Int,Unit], f: Int => Boolean) =>
        val (chunks, unfinished, tail) = s.force.splitWhile(f) match {
          case Left((_,chunks)) => (chunks, true, Segment.empty)
          case Right((chunks,tail)) => (chunks, false, tail)
        }
        Segment.catenatedChunks(chunks).force.toVector shouldBe s.force.toVector.takeWhile(f)
        unfinished shouldBe (s.force.toVector.takeWhile(f).size == s.force.toVector.size)
        val remainder = s.force.toVector.dropWhile(f)
        if (remainder.isEmpty) tail shouldBe Segment.empty
        else tail.force.toVector shouldBe remainder
      }
    }

    "splitWhile (2)" in {
      forAll { (s: Segment[Int,Unit], f: Int => Boolean) =>
        val (chunks, unfinished, tail) = s.force.splitWhile(f, emitFailure = true) match {
          case Left((_,chunks)) => (chunks, true, Segment.empty)
          case Right((chunks,tail)) => (chunks, false, tail)
        }
        val svec = s.force.toVector
        Segment.catenatedChunks(chunks).force.toVector shouldBe (svec.takeWhile(f) ++ svec.dropWhile(f).headOption)
        unfinished shouldBe (svec.takeWhile(f).size == svec.size)
        val remainder = svec.dropWhile(f).drop(1)
        if (remainder.isEmpty) tail.force.toVector shouldBe Vector.empty
        else tail.force.toVector shouldBe remainder
      }
    }

    "splitWhile (3)" in {
      val (prefix, suffix) = Segment.seq(List.range(1,10)).map(_ - 1).force.splitWhile(_ < 5).toOption.get
      Segment.catenatedChunks(prefix).force.toVector shouldBe Vector(0, 1, 2, 3, 4)
      suffix.force.toVector shouldBe Vector(5, 6, 7, 8)
    }

    "sum" in {
      forAll { (s: Segment[Int,Unit]) =>
        s.sum.force.run shouldBe s.force.toVector.sum
      }
    }

    "take" in {
      forAll { (s: Segment[Int,Unit], n: Int) =>
        val v = s.force.toVector
        s.take(n).force.toVector shouldBe v.take(n)
        if (n > 0 && n > v.size) {
          s.take(n).drain.force.run shouldBe Left(((), n - v.size))
        } else {
          s.take(n).drain.force.run.map(_.force.toVector) shouldBe Right(Segment.vector(v.drop(n)).force.toVector)
        }
      }
    }

    "take eagerly exits" in {
      Segment.from(0L).filter(_ < 2L).take(2).force.toVector shouldBe Vector(0L, 1L)
    }

    "takeWhile" in {
      forAll { (s: Segment[Int,Unit], f: Int => Boolean) =>
        s.takeWhile(f).force.toVector shouldBe s.force.toVector.takeWhile(f)
      }
    }

    "takeWhile (2)" in {
      forAll { (s: Segment[Int,Unit], f: Int => Boolean) =>
        s.takeWhile(f, true).force.toVector shouldBe (s.force.toVector.takeWhile(f) ++ s.force.toVector.dropWhile(f).headOption)
      }
    }

    "takeWhile (3)" in {
      Segment.catenated(Segment.unfold(0)(i => if (i < 1000) Some((i, i + 1)) else None).takeWhile(_ != 5, true).force.unconsAll._1.map(Segment.chunk)).force.toList shouldBe List(0, 1, 2, 3, 4, 5)
    }

    "uncons" in {
      forAll { (xss: List[List[Int]]) =>
        val seg = xss.foldRight(Segment.empty[Int])((xs, acc) => Segment.array(xs.toArray) ++ acc)
        // Consecutive empty chunks are collapsed to a single empty chunk
        seg.force.unconsAll._1.toList.map(_.toList).filter(_.nonEmpty) shouldBe xss.filter(_.nonEmpty)
      }
    }

    "uncons1 performance" - {
      val Ns = List(2,3,100,200,400,800,1600,3200,6400,12800,25600,51200,102400)
      Ns.foreach { N => N.toString in {
        val s = Segment.from(0).take(N).voidResult
        def go(s: Segment[Long,Unit]): Unit = s.force.uncons1 match {
          case Right((hd,tl)) => go(tl)
          case Left(_) => ()
        }
        go(s)
      }}
    }

    "zipWith" in {
      forAll { (xs: Segment[Int,Unit], ys: Segment[Int,Unit]) =>
        val xsv = xs.force.toVector
        val ysv = ys.force.toVector
        val f: (Int,Int) => (Int,Int) = (_,_)
        val (segments, leftover) = xs.zipWith(ys)(f).force.unconsAll
        segments.toVector.flatMap(_.toVector) shouldBe xsv.zip(ysv).map { case (x,y) => f(x,y) }
        leftover match {
          case Left((_,leftoverYs)) => withClue("leftover ys")(leftoverYs.force.toVector shouldBe ysv.drop(xsv.size))
          case Right((_,leftoverXs)) => withClue("leftover xs")(leftoverXs.force.toVector shouldBe xsv.drop(ysv.size))
        }
      }
    }

    "staging stack safety" in {
      val N = 100000
      val s = (0 until N).foldLeft(Segment.singleton(0))((s,i) => s map (_ + i))
      s.sum.force.run shouldBe (0 until N).sum
    }

    val Ns = List(2,3,100,200,400,800,1600,3200,6400,12800,25600,51200,102400)
    "uncons1 is O(1)" - { Ns.foreach { N =>
      N.toString in {
        def go[O,R](s: Segment[O,R]): R =
          s.force.uncons1 match {
            case Left(r) => r
            case Right((o, s)) => go(s)
          }
        go(Segment.indexedSeq(0 until N))
      }
    }}
  }
}
