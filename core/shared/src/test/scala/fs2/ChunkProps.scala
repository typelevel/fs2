package fs2

import scala.reflect.ClassTag

import org.scalacheck.Arbitrary
import org.scalatest.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import TestUtil._

object ChunkProps
    extends Matchers
    with ScalaCheckDrivenPropertyChecks {
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

  def propIsEmpty[A: Arbitrary, C <: Chunk[A]: Arbitrary] =
    forAll { c: C =>
      c.isEmpty shouldBe c.toVector.isEmpty
    }

  def propToArray[A: ClassTag: Arbitrary, C <: Chunk[A]: Arbitrary] =
    forAll { c: C =>
      c.toArray.toVector shouldBe c.toVector
      // Do it twice to make sure the first time didn't mutate state
      c.toArray.toVector shouldBe c.toVector
    }

  def propCopyToArray[A: ClassTag: Arbitrary, C <: Chunk[A]: Arbitrary] =
    forAll { c: C =>
      val arr = new Array[A](c.size * 2)
      c.copyToArray(arr, 0)
      c.copyToArray(arr, c.size)
      arr.toVector shouldBe (c.toVector ++ c.toVector)
    }

  def propConcat[A: ClassTag: Arbitrary, C <: Chunk[A]: Arbitrary] =
    forAll { (c1: C, c2: C) =>
      Chunk.concat(List(Chunk.empty, c1, Chunk.empty, c2)).toVector shouldBe (c1.toVector ++ c2.toVector)
    }

  def propToByteBuffer[C <: Chunk[Byte]: Arbitrary] =
    forAll { c: C =>
      val arr = new Array[Byte](c.size)
      c.toByteBuffer.get(arr, 0, c.size)
      arr.toVector shouldBe c.toArray.toVector
    }
}
