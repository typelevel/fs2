package fs2

import cats.Eq
import cats.kernel.CommutativeMonoid
import cats.kernel.laws.discipline.EqTests
import cats.laws.discipline.{ MonadTests, TraverseTests }
import cats.implicits._
import java.nio.ByteBuffer
import org.scalacheck.{ Arbitrary, Cogen, Gen }
import scala.reflect.ClassTag

import TestUtil._
import ChunkProps._

class ChunkSpec extends Fs2Spec {

  "Chunk" - {
    "chunk-formation (1)" in {
      Chunk.empty.toList shouldBe List()
      Chunk.singleton(23).toList shouldBe List(23)
    }

    "chunk-formation (2)" in forAll { (c: Vector[Int]) =>
      Chunk.seq(c).toVector shouldBe c
      Chunk.seq(c).toList shouldBe c.toList
      Chunk.indexedSeq(c).toVector shouldBe c
      Chunk.indexedSeq(c).toList shouldBe c.toList
    }
  }

  implicit val arbBooleanChunk: Arbitrary[Chunk[Boolean]] = Arbitrary {
    for {
      n <- Gen.choose(0, 100)
      values <- Gen.containerOfN[Array, Boolean](n, Arbitrary.arbBool.arbitrary)
      offset <- Gen.choose(0, n)
      sz <- Gen.choose(0, n - offset)
    } yield Chunk.Booleans(values, offset, sz)
  }

  implicit val arbByteChunk: Arbitrary[Chunk[Byte]] = Arbitrary {
    for {
      n <- Gen.choose(0, 100)
      values <- Gen.containerOfN[Array, Byte](n, Arbitrary.arbByte.arbitrary)
      offset <- Gen.choose(0, n)
      sz <- Gen.choose(0, n - offset)
    } yield Chunk.bytes(values, offset, sz)
  }

  val arbByteBufferChunk: Arbitrary[Chunk[Byte]] = Arbitrary {
    for {
      n <- Gen.choose(0, 100)
      values <- Gen.containerOfN[Array, Byte](n, Arbitrary.arbByte.arbitrary)
      pos <- Gen.choose(0, n)
      lim <- Gen.choose(pos, n)
      direct <- Arbitrary.arbBool.arbitrary
      bb = if (direct) ByteBuffer.allocateDirect(n).put(values) else ByteBuffer.wrap(values)
      _ = bb.position(pos).limit(lim)
    } yield Chunk.byteBuffer(bb)
  }

  implicit val arbShortChunk: Arbitrary[Chunk[Short]] = Arbitrary {
    for {
      n <- Gen.choose(0, 100)
      values <- Gen.containerOfN[Array, Short](n, Arbitrary.arbShort.arbitrary)
      offset <- Gen.choose(0, n)
      sz <- Gen.choose(0, n - offset)
    } yield Chunk.shorts(values, offset, sz)
  }

  implicit val arbIntChunk: Arbitrary[Chunk[Int]] = Arbitrary {
    for {
      n <- Gen.choose(0, 100)
      values <- Gen.containerOfN[Array, Int](n, Arbitrary.arbInt.arbitrary)
      offset <- Gen.choose(0, n)
      sz <- Gen.choose(0, n - offset)
    } yield Chunk.ints(values, offset, sz)
  }

  implicit val arbLongChunk: Arbitrary[Chunk[Long]] = Arbitrary {
    for {
      n <- Gen.choose(0, 100)
      values <- Gen.containerOfN[Array, Long](n, Arbitrary.arbLong.arbitrary)
      offset <- Gen.choose(0, n)
      sz <- Gen.choose(0, n - offset)
    } yield Chunk.longs(values, offset, sz)
  }

  implicit val arbFloatChunk: Arbitrary[Chunk[Float]] = Arbitrary {
    for {
      n <- Gen.choose(0, 100)
      values <- Gen.containerOfN[Array, Float](n, Arbitrary.arbFloat.arbitrary)
      offset <- Gen.choose(0, n)
      sz <- Gen.choose(0, n - offset)
    } yield Chunk.floats(values, offset, sz)
  }

  implicit val arbDoubleChunk: Arbitrary[Chunk[Double]] = Arbitrary {
    for {
      n <- Gen.choose(0, 100)
      values <- Gen.containerOfN[Array, Double](n, Arbitrary.arbDouble.arbitrary)
      offset <- Gen.choose(0, n)
      sz <- Gen.choose(0, n - offset)
    } yield Chunk.doubles(values, offset, sz)
  }

  def testChunk[A: Arbitrary: ClassTag: Cogen: CommutativeMonoid: Eq](name: String, of: String, testTraverse: Boolean = true)(implicit C: Arbitrary[Chunk[A]]): Unit = {
    s"$name" - {
      "size" in propSize[A, Chunk[A]]
      "take" in propTake[A, Chunk[A]]
      "drop" in propDrop[A, Chunk[A]]
      "isempty" in propIsEmpty[A, Chunk[A]]
      "toarray" in propToArray[A, Chunk[A]]

      if (implicitly[ClassTag[A]] == ClassTag.Byte)
        "tobytebuffer.byte" in propToByteBuffer[Chunk[Byte]]

      checkAll(s"Eq[Chunk[$of]]", EqTests[Chunk[A]].eqv)
      checkAll(s"Monad[Chunk]", MonadTests[Chunk].monad[A, A, A])

      if (testTraverse)
        checkAll(s"Traverse[Chunk]", TraverseTests[Chunk].traverse[A, A, A, A, Option, Option])
    }
  }

  implicit val commutativeMonoidForChar = new CommutativeMonoid[Char] {
    def combine(x: Char, y: Char): Char = (x + y).toChar
    def empty: Char = 0
  }

  testChunk[Byte]("Bytes", "Byte")
  testChunk[Short]("Shorts", "Short")
  testChunk[Int]("Ints", "Int")
  testChunk[Long]("Longs", "Long")
  // Don't test traverse on Double or Float. They have naughty monoids.
  testChunk[Double]("Doubles", "Double", false)
  testChunk[Float]("Floats", "Float", false)
  testChunk[Char]("Unspecialized", "Char")
  testChunk[Byte]("ByteBuffer", "Byte")(implicitly, implicitly, implicitly, implicitly, implicitly, arbByteBufferChunk)
}
