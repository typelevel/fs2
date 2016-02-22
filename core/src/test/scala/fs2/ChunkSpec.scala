package fs2

import fs2.Chunk.{Longs, Doubles, Bytes, Booleans}
import fs2.TestUtil._
import org.scalacheck.Prop._
import org.scalacheck.{Gen, Arbitrary, Properties}

import scala.reflect.ClassTag

object ChunkSpec extends Properties("chunk") {

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

  private def checkSize[A](c: Chunk[A]): Boolean =
    c.size == c.toVector.size

  property("size.boolean") = forAll { c: Chunk[Boolean] => checkSize(c) }
  property("size.byte") = forAll { c: Chunk[Byte] => checkSize(c) }
  property("size.double") = forAll { c: Chunk[Double] => checkSize(c) }
  property("size.long") = forAll { c: Chunk[Long] => checkSize(c) }
  property("size.unspecialized") = forAll { c: Chunk[Int] => checkSize(c) }

  private def checkTake[A](c: Chunk[A], n: Int): Boolean =
    c.take(n).toVector == c.toVector.take(n)

  property("take.boolean") = forAll { (c: Chunk[Boolean], n: SmallNonnegative) => checkTake(c, n.get) }
  property("take.byte") = forAll { (c: Chunk[Byte], n: SmallNonnegative) => checkTake(c, n.get) }
  property("take.double") = forAll {(c: Chunk[Double], n: SmallNonnegative) => checkTake(c, n.get) }
  property("take.long") = forAll { (c: Chunk[Long], n: SmallNonnegative) => checkTake(c, n.get) }
  property("take.unspecialized") = forAll { (c: Chunk[Int], n: SmallNonnegative) => checkTake(c, n.get) }

  private def checkDrop[A](c: Chunk[A], n: Int): Boolean =
    c.drop(n).toVector == c.toVector.drop(n)

  property("drop.boolean") = forAll { (c: Chunk[Boolean], n: SmallNonnegative) => checkDrop(c, n.get) }
  property("drop.byte") = forAll { (c: Chunk[Byte], n: SmallNonnegative) => checkDrop(c, n.get) }
  property("drop.double") = forAll {(c: Chunk[Double], n: SmallNonnegative) => checkDrop(c, n.get) }
  property("drop.long") = forAll { (c: Chunk[Long], n: SmallNonnegative) => checkDrop(c, n.get) }
  property("drop.unspecialized") = forAll { (c: Chunk[Int], n: SmallNonnegative) => checkDrop(c, n.get) }

  private def checkUncons[A](c: Chunk[A]): Boolean = {
    if (c.toVector.isEmpty)
      c.uncons.isEmpty
    else
      c.uncons.contains((c(0), c.drop(1)))
  }

  property("uncons.boolean") = forAll { c: Chunk[Boolean] => checkUncons(c) }
  property("uncons.byte") = forAll { c: Chunk[Byte] => checkUncons(c) }
  property("uncons.double") = forAll { c: Chunk[Double] => checkUncons(c) }
  property("uncons.long") = forAll { c: Chunk[Long] => checkUncons(c) }
  property("uncons.unspecialized") = forAll { c: Chunk[Int] => checkUncons(c) }

  private def checkIsEmpty[A](c: Chunk[A]): Boolean =
    c.isEmpty == c.toVector.isEmpty

  property("isempty.boolean") = forAll { c: Chunk[Boolean] => checkIsEmpty(c) }
  property("isempty.byte") = forAll { c: Chunk[Byte] => checkIsEmpty(c) }
  property("isempty.double") = forAll { c: Chunk[Double] => checkIsEmpty(c) }
  property("isempty.long") = forAll { c: Chunk[Long] => checkIsEmpty(c) }
  property("isempty.unspecialized") = forAll { c: Chunk[Int] => checkIsEmpty(c) }

  private def checkFilter[A](c: Chunk[A], pred: A => Boolean): Boolean =
    c.filter(pred).toVector == c.toVector.filter(pred)

  property("filter.boolean") = forAll { c: Chunk[Boolean] =>
    val predicate = (b: Boolean) => !b
    checkFilter(c, predicate)
  }
  property("filter.byte") = forAll { c: Chunk[Byte] =>
    val predicate = (b: Byte) => b < 0
    checkFilter(c, predicate)
  }
  property("filter.double") = forAll { c: Chunk[Double] =>
    val predicate = (i: Double) => i - i.floor < 0.5
    checkFilter(c, predicate)
  }
  property("filter.long") = forAll { (c: Chunk[Long], n: SmallPositive) =>
    val predicate = (i: Long) => i % n.get == 0
    checkFilter(c, predicate)
  }
  property("filter.unspecialized") = forAll { (c: Chunk[Int], n: SmallPositive) =>
    val predicate = (i: Int) => i % n.get == 0
    checkFilter(c, predicate)
  }

  private def checkFoldLeft[A, B](c: Chunk[A], z: B)(f: (B, A) => B): Boolean =
    c.foldLeft(z)(f) == c.toVector.foldLeft(z)(f)

  property("foldleft.boolean") = forAll { c: Chunk[Boolean] =>
    val f = (b: Boolean) => if (b) 1 else 0
    checkFoldLeft(c, 0L)(_ + f(_))
  }
  property("foldleft.byte") = forAll { c: Chunk[Byte] =>
    checkFoldLeft(c, 0L)(_ + _.toLong)
  }
  property("foldleft.double") = forAll { c: Chunk[Double] => checkFoldLeft(c, 0.0)(_ + _) }
  property("foldleft.long") = forAll { c: Chunk[Long] => checkFoldLeft(c, 0L)(_ + _) }
  property("foldleft.unspecialized") = forAll { c: Chunk[Int] => checkFoldLeft(c, 0L)(_ + _) }

  private def checkFoldRight[A, B](c: Chunk[A], z: B)(f: (A, B) => B): Boolean =
    c.foldRight(z)(f) == c.toVector.foldRight(z)(f)

  property("foldright.boolean") = forAll { c: Chunk[Boolean] =>
    val f = (b: Boolean) => if (b) 1 else 0
    checkFoldRight(c, 0L)(f(_) + _)
  }
  property("foldright.byte") = forAll { c: Chunk[Byte] =>
    checkFoldRight(c, 0L)(_.toLong + _)
  }
  property("foldright.double") = forAll { c: Chunk[Double] => checkFoldRight(c, 0.0)(_ + _) }
  property("foldright.long") = forAll { c: Chunk[Long] => checkFoldRight(c, 0L)(_ + _) }
  property("foldright.unspecialized") = forAll { c: Chunk[Int] => checkFoldRight(c, 0L)(_ + _) }

  private def checkToArray[A: ClassTag](c: Chunk[A]): Boolean =
    c.toArray.toVector == c.toVector

  property("toarray.boolean") = forAll { c: Chunk[Boolean] => checkToArray(c) }
  property("toarray.byte") = forAll { c: Chunk[Byte] => checkToArray(c) }
  property("toarray.double") = forAll { c: Chunk[Double] => checkToArray(c) }
  property("toarray.long") = forAll { c: Chunk[Long] => checkToArray(c) }
  property("toarray.unspecialized") = forAll { c: Chunk[Int] => checkToArray(c) }
}