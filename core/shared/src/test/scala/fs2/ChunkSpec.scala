package fs2

import cats.Eq
import cats.kernel.CommutativeMonoid
import cats.kernel.laws.discipline.EqTests
import cats.laws.discipline.{FunctorFilterTests, MonadTests, TraverseTests}
import cats.implicits._
import org.scalacheck.{Arbitrary, Cogen, Gen}

import scala.reflect.ClassTag
import scala.util.control.NonFatal
import TestUtil._
import ChunkProps._

class ChunkSpec extends Fs2Spec {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = if (isJVM) 300 else 50, workers = 1)

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

    "Chunk.apply is optimized" in {
      Chunk(1) shouldBe a[Chunk.Singleton[_]]
      Chunk("Hello") shouldBe a[Chunk.Singleton[_]]
      // Varargs on Scala.js use a scala.scalajs.js.WrappedArray, which
      // ends up falling through to the Chunk.indexedSeq constructor
      if (isJVM) {
        Chunk(1, 2, 3) shouldBe a[Chunk.Ints]
        Chunk("Hello", "world") shouldBe a[Chunk.Boxed[_]]
      }
    }

    "Chunk.seq is optimized" in {
      Chunk.seq(List(1)) shouldBe a[Chunk.Singleton[_]]
    }

    "Array casts in Chunk.seq are safe" in {
      val as = collection.mutable.ArraySeq[Int](0, 1, 2)
      val c = Chunk.seq(as)
      try c shouldBe a[Chunk.Boxed[_]] // 2.11/2.12
      catch {
        case NonFatal(t) => c shouldBe a[Chunk.Ints] // 2.13+
      }
    }
  }

  def testChunk[A: Arbitrary: ClassTag: Cogen: CommutativeMonoid: Eq](genChunk: Gen[Chunk[A]], name: String, of: String, testTraverse: Boolean = true): Unit = {
    s"$name" - {
      // borrowed from ScalaCheck 1.14
      // TODO remove when the project upgrades to ScalaCheck 1.14
      implicit def arbPartialFunction[B: Arbitrary]: Arbitrary[PartialFunction[A, B]] =
        Arbitrary(implicitly[Arbitrary[A => Option[B]]].arbitrary.map(Function.unlift))

      implicit val arbChunk: Arbitrary[Chunk[A]] = Arbitrary(genChunk)
      "size" in propSize[A, Chunk[A]]
      "take" in propTake[A, Chunk[A]]
      "drop" in propDrop[A, Chunk[A]]
      "isEmpty" in propIsEmpty[A, Chunk[A]]
      "toArray" in propToArray[A, Chunk[A]]
      "copyToArray" in propCopyToArray[A, Chunk[A]]
      "concat" in propConcat[A, Chunk[A]]

      if (implicitly[ClassTag[A]] == ClassTag.Byte)
        "toByteBuffer.byte" in propToByteBuffer[Chunk[Byte]]

      checkAll(s"Eq[Chunk[$of]]", EqTests[Chunk[A]].eqv)
      checkAll(s"Monad[Chunk]", MonadTests[Chunk].monad[A, A, A])
      checkAll(s"FunctorFilter[Chunk]", FunctorFilterTests[Chunk].functorFilter[A, A, A])

      if (testTraverse)
        checkAll(s"Traverse[Chunk]", TraverseTests[Chunk].traverse[A, A, A, A, Option, Option])
    }
  }

  implicit val commutativeMonoidForChar = new CommutativeMonoid[Char] {
    def combine(x: Char, y: Char): Char = (x + y).toChar
    def empty: Char = 0
  }

  testChunk[Byte](genByteChunk, "Bytes", "Byte")
  testChunk[Short](genShortChunk, "Shorts", "Short")
  testChunk[Int](genIntChunk, "Ints", "Int")
  testChunk[Long](genLongChunk, "Longs", "Long")
  // Don't test traverse on Double or Float. They have naughty monoids.
  testChunk[Double](genDoubleChunk, "Doubles", "Double", false)
  testChunk[Float](genFloatChunk, "Floats", "Float", false)
  testChunk[Char](genCharChunk, "Unspecialized", "Char")

  testChunk[Byte](genByteBufferChunk, "ByteBuffer", "Byte")
  testChunk[Byte](genByteVectorChunk, "ByteVector", "Byte")
  testChunk[Short](genShortBufferChunk, "ShortBuffer", "Short")
  testChunk[Int](genIntBufferChunk, "IntBuffer", "Int")
  testChunk[Long](genLongBufferChunk, "LongBuffer", "Long")
  testChunk[Double](genDoubleBufferChunk, "DoubleBuffer", "Double", false)
  testChunk[Float](genFloatBufferChunk, "FloatBuffer", "Float", false)
  testChunk[Char](genCharBufferChunk, "CharBuffer", "Char")
}
