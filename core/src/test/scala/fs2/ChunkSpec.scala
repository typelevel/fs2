package fs2

import fs2.Chunk.{Longs, Doubles, Bytes, Booleans}
import org.scalacheck.{Gen, Arbitrary}

import scala.reflect.ClassTag

class ChunkSpec extends Fs2Spec {

  "Chunk" - {

    implicit val arbBooleanChunk: Arbitrary[Chunk[Boolean]] = Arbitrary {
      for {
        n <- Gen.choose(0, 100)
        values <- Gen.containerOfN[Array, Boolean](n, Arbitrary.arbBool.arbitrary)
        offset <- Gen.choose(0, n)
        sz <- Gen.choose(0, n - offset)
      } yield new Booleans(values, offset, sz)
    }

    implicit val arbByteChunk: Arbitrary[Chunk[Byte]] = Arbitrary {
      for {
        n <- Gen.choose(0, 100)
        values <- Gen.containerOfN[Array, Byte](n, Arbitrary.arbByte.arbitrary)
        offset <- Gen.choose(0, n)
        sz <- Gen.choose(0, n - offset)
      } yield new Bytes(values, offset, sz)
    }

    implicit val arbDoubleChunk: Arbitrary[Chunk[Double]] = Arbitrary {
      for {
        n <- Gen.choose(0, 100)
        values <- Gen.containerOfN[Array, Double](n, Arbitrary.arbDouble.arbitrary)
        offset <- Gen.choose(0, n)
        sz <- Gen.choose(0, n - offset)
      } yield new Doubles(values, offset, sz)
    }

    implicit val arbLongChunk: Arbitrary[Chunk[Long]] = Arbitrary {
      for {
        n <- Gen.choose(0, 100)
        values <- Gen.containerOfN[Array, Long](n, Arbitrary.arbLong.arbitrary)
        offset <- Gen.choose(0, n)
        sz <- Gen.choose(0, n - offset)
      } yield new Longs(values, offset, sz)
    }

    def checkSize[A](c: Chunk[A]) = c.size shouldBe c.toVector.size

    "size.boolean" in forAll { c: Chunk[Boolean] => checkSize(c) }
    "size.byte" in forAll { c: Chunk[Byte] => checkSize(c) }
    "size.double" in forAll { c: Chunk[Double] => checkSize(c) }
    "size.long" in forAll { c: Chunk[Long] => checkSize(c) }
    "size.unspecialized" in forAll { c: Chunk[Int] => checkSize(c) }

    def checkTake[A](c: Chunk[A], n: Int) =
      c.take(n).toVector shouldBe c.toVector.take(n)

    "take.boolean" in forAll { (c: Chunk[Boolean], n: SmallNonnegative) => checkTake(c, n.get) }
    "take.byte" in forAll { (c: Chunk[Byte], n: SmallNonnegative) => checkTake(c, n.get) }
    "take.double" in forAll {(c: Chunk[Double], n: SmallNonnegative) => checkTake(c, n.get) }
    "take.long" in forAll { (c: Chunk[Long], n: SmallNonnegative) => checkTake(c, n.get) }
    "take.unspecialized" in forAll { (c: Chunk[Int], n: SmallNonnegative) => checkTake(c, n.get) }

    def checkDrop[A](c: Chunk[A], n: Int) =
      c.drop(n).toVector shouldBe c.toVector.drop(n)

    "drop.boolean" in forAll { (c: Chunk[Boolean], n: SmallNonnegative) => checkDrop(c, n.get) }
    "drop.byte" in forAll { (c: Chunk[Byte], n: SmallNonnegative) => checkDrop(c, n.get) }
    "drop.double" in forAll {(c: Chunk[Double], n: SmallNonnegative) => checkDrop(c, n.get) }
    "drop.long" in forAll { (c: Chunk[Long], n: SmallNonnegative) => checkDrop(c, n.get) }
    "drop.unspecialized" in forAll { (c: Chunk[Int], n: SmallNonnegative) => checkDrop(c, n.get) }

    def checkUncons[A](c: Chunk[A]) = assert {
      if (c.toVector.isEmpty)
        c.uncons.isEmpty
      else
        c.uncons.contains((c(0), c.drop(1)))
    }

    "uncons.boolean" in forAll { c: Chunk[Boolean] => checkUncons(c) }
    "uncons.byte" in forAll { c: Chunk[Byte] => checkUncons(c) }
    "uncons.double" in forAll { c: Chunk[Double] => checkUncons(c) }
    "uncons.long" in forAll { c: Chunk[Long] => checkUncons(c) }
    "uncons.unspecialized" in forAll { c: Chunk[Int] => checkUncons(c) }

    def checkIsEmpty[A](c: Chunk[A]) =
      c.isEmpty shouldBe c.toVector.isEmpty

    "isempty.boolean" in forAll { c: Chunk[Boolean] => checkIsEmpty(c) }
    "isempty.byte" in forAll { c: Chunk[Byte] => checkIsEmpty(c) }
    "isempty.double" in forAll { c: Chunk[Double] => checkIsEmpty(c) }
    "isempty.long" in forAll { c: Chunk[Long] => checkIsEmpty(c) }
    "isempty.unspecialized" in forAll { c: Chunk[Int] => checkIsEmpty(c) }

    def checkFilter[A](c: Chunk[A], pred: A => Boolean) =
      c.filter(pred).toVector shouldBe c.toVector.filter(pred)

    "filter.boolean" in forAll { c: Chunk[Boolean] =>
      val predicate = (b: Boolean) => !b
      checkFilter(c, predicate)
    }
    "filter.byte" in forAll { c: Chunk[Byte] =>
      val predicate = (b: Byte) => b < 0
      checkFilter(c, predicate)
    }
    "filter.double" in forAll { c: Chunk[Double] =>
      val predicate = (i: Double) => i - i.floor < 0.5
      checkFilter(c, predicate)
    }
    "filter.long" in forAll { (c: Chunk[Long], n: SmallPositive) =>
      val predicate = (i: Long) => i % n.get == 0
      checkFilter(c, predicate)
    }
    "filter.unspecialized" in forAll { (c: Chunk[Int], n: SmallPositive) =>
      val predicate = (i: Int) => i % n.get == 0
      checkFilter(c, predicate)
    }

    def checkFoldLeft[A, B](c: Chunk[A], z: B)(f: (B, A) => B) =
      c.foldLeft(z)(f) shouldBe c.toVector.foldLeft(z)(f)

    "foldleft.boolean" in forAll { c: Chunk[Boolean] =>
      val f = (b: Boolean) => if (b) 1 else 0
      checkFoldLeft(c, 0L)(_ + f(_))
    }
    "foldleft.byte" in forAll { c: Chunk[Byte] =>
      checkFoldLeft(c, 0L)(_ + _.toLong)
    }
    "foldleft.double" in forAll { c: Chunk[Double] => checkFoldLeft(c, 0.0)(_ + _) }
    "foldleft.long" in forAll { c: Chunk[Long] => checkFoldLeft(c, 0L)(_ + _) }
    "foldleft.unspecialized" in forAll { c: Chunk[Int] => checkFoldLeft(c, 0L)(_ + _) }

    def checkFoldRight[A, B](c: Chunk[A], z: B)(f: (A, B) => B) =
      c.foldRight(z)(f) shouldBe c.toVector.foldRight(z)(f)

    "foldright.boolean" in forAll { c: Chunk[Boolean] =>
      val f = (b: Boolean) => if (b) 1 else 0
      checkFoldRight(c, 0L)(f(_) + _)
    }
    "foldright.byte" in forAll { c: Chunk[Byte] =>
      checkFoldRight(c, 0L)(_.toLong + _)
    }
    "foldright.double" in forAll { c: Chunk[Double] => checkFoldRight(c, 0.0)(_ + _) }
    "foldright.long" in forAll { c: Chunk[Long] => checkFoldRight(c, 0L)(_ + _) }
    "foldright.unspecialized" in forAll { c: Chunk[Int] => checkFoldRight(c, 0L)(_ + _) }

    def checkToArray[A: ClassTag](c: Chunk[A]) =
      c.toArray.toVector shouldBe c.toVector

    "toarray.boolean" in forAll { c: Chunk[Boolean] => checkToArray(c) }
    "toarray.byte" in forAll { c: Chunk[Byte] => checkToArray(c) }
    "toarray.double" in forAll { c: Chunk[Double] => checkToArray(c) }
    "toarray.long" in forAll { c: Chunk[Long] => checkToArray(c) }
    "toarray.unspecialized" in forAll { c: Chunk[Int] => checkToArray(c) }
  }
}
