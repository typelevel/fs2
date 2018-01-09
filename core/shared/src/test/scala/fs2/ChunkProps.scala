package fs2

import scala.reflect.ClassTag

import org.scalacheck.Arbitrary
import org.scalatest.Matchers
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import TestUtil._

object ChunkProps
    extends Matchers
    with GeneratorDrivenPropertyChecks {
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
    }

  def propToByteBuffer[C <: Chunk[Byte]: Arbitrary] =
    forAll { c: C =>
      val arr = new Array[Byte](c.size)
      c.toByteBuffer.get(arr, 0, c.size)
      arr.toVector shouldBe c.toArray.toVector
    }
}
