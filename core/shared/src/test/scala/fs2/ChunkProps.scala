package fs2

import scala.reflect.ClassTag

import org.scalacheck.Arbitrary
import org.scalatest.Matchers
import org.scalatest.prop.GeneratorDrivenPropertyChecks

object ChunkProps
    extends Matchers
    with GeneratorDrivenPropertyChecks
    with TestUtil
{
  def propSize[A: Arbitrary, C <: Chunk[A]: Arbitrary] =
    forAll { c: C =>
      c.size shouldBe c.toVector.size
    }

  def propTake[A: Arbitrary, C <: Chunk[A]: Arbitrary] =
    forAll { (c: C, n: SmallNonnegative) =>
      c.take(n.get).toVector shouldBe c.toVector.take(n.get)
    }

  def propDrop[A: Arbitrary, C <: Chunk[A]: Arbitrary] =
    forAll { (c: C, n: SmallNonnegative) =>
      c.drop(n.get).toVector shouldBe c.toVector.drop(n.get)
    }

  def propUncons[A: Arbitrary, C <: Chunk[A]: Arbitrary] =
    forAll { c: C =>
      if (c.toVector.isEmpty)
        c.uncons.isEmpty
      else
        c.uncons.contains((c(0), c.drop(1)))
    }

  def propIsEmpty[A: Arbitrary, C <: Chunk[A]: Arbitrary] =
    forAll { c: C =>
      c.isEmpty shouldBe c.toVector.isEmpty
    }

  def propFilter[A: Arbitrary, C <: Chunk[A]: Arbitrary](implicit F: Arbitrary[A => Boolean]) =
    forAll { (c: C, pred: A => Boolean) =>
      c.filter(pred).toVector shouldBe c.toVector.filter(pred)
    }

  def propFoldLeft[A: Arbitrary, C <: Chunk[A]: Arbitrary](implicit F: Arbitrary[(Long, A) => Long]) =
    forAll { (c: C, z: Long, f: (Long, A) => Long) =>
      c.foldLeft(z)(f) shouldBe c.toVector.foldLeft(z)(f)
    }

  def propFoldRight[A: Arbitrary, C <: Chunk[A]: Arbitrary](implicit F: Arbitrary[(A, Long) => Long]) =
    forAll { (c: C, z: Long, f: (A, Long) => Long) =>
      c.foldRight(z)(f) shouldBe c.toVector.foldRight(z)(f)
    }

  def propToArray[A: ClassTag: Arbitrary, C <: Chunk[A]: Arbitrary] =
    forAll { c: C =>
      c.toArray.toVector shouldBe c.toVector
    }

  def propConcat[A: Arbitrary, C <: Chunk[A]: Arbitrary: ClassTag] =
    forAll { cs: List[C] =>
      val result = Chunk.concat(cs)
      result.toVector shouldBe cs.foldLeft(Vector.empty[A])(_ ++ _.toVector)
      if (!result.isEmpty) result shouldBe a[C]
      result
    }
}
