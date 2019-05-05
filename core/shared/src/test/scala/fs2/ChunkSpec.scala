package fs2

import cats.Eq
import cats.kernel.CommutativeMonoid
import cats.kernel.laws.discipline.EqTests
import cats.laws.discipline.{FunctorFilterTests, MonadTests, TraverseTests}
import cats.implicits._
import org.scalacheck.Cogen
import org.scalactic.anyvals._
import org.scalatest.Succeeded
import org.scalatest.prop.Generator
import scala.reflect.ClassTag
import scala.util.control.NonFatal

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
      } else {
        Succeeded
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

  def testChunk[A: Generator: ClassTag: CommutativeMonoid: Eq: Cogen](
      genChunk: Generator[Chunk[A]],
      name: String,
      of: String,
      testTraverse: Boolean = true): Unit =
    s"$name" - {
      implicit val implicitChunkGenerator: Generator[Chunk[A]] = genChunk
      "size" in forAll { (c: Chunk[A]) =>
        c.size shouldBe c.toList.size
      }
      "take" in forAll { (c: Chunk[A], n: PosZInt) =>
        c.take(n).toVector shouldBe c.toVector.take(n)
      }
      "drop" in forAll { (c: Chunk[A], n: PosZInt) =>
        c.drop(n).toVector shouldBe c.toVector.drop(n)
      }
      "isEmpty" in forAll { (c: Chunk[A]) =>
        c.isEmpty shouldBe c.toList.isEmpty
      }
      "toArray" in forAll { c: Chunk[A] =>
        c.toArray.toVector shouldBe c.toVector
        // Do it twice to make sure the first time didn't mutate state
        c.toArray.toVector shouldBe c.toVector
      }
      "copyToArray" in forAll { c: Chunk[A] =>
        val arr = new Array[A](c.size * 2)
        c.copyToArray(arr, 0)
        c.copyToArray(arr, c.size)
        arr.toVector shouldBe (c.toVector ++ c.toVector)
      }
      "concat" in forAll { (c1: Chunk[A], c2: Chunk[A]) =>
        Chunk
          .concat(List(Chunk.empty, c1, Chunk.empty, c2))
          .toVector shouldBe (c1.toVector ++ c2.toVector)
      }

      if (implicitly[ClassTag[A]] == ClassTag.Byte)
        "toByteBuffer.byte" in forAll { c: Chunk[A] =>
          implicit val ev: A =:= Byte = null
          val arr = new Array[Byte](c.size)
          c.toByteBuffer.get(arr, 0, c.size)
          arr.toVector shouldBe c.toArray.toVector
        }

      import org.scalacheck.GeneratorCompat._

      checkAll(s"Eq[Chunk[$of]]", EqTests[Chunk[A]].eqv)
      checkAll(s"Monad[Chunk]", MonadTests[Chunk].monad[A, A, A])
      checkAll(s"FunctorFilter[Chunk]", FunctorFilterTests[Chunk].functorFilter[A, A, A])

      if (testTraverse)
        checkAll(s"Traverse[Chunk]", TraverseTests[Chunk].traverse[A, A, A, A, Option, Option])
    }

  implicit val commutativeMonoidForChar = new CommutativeMonoid[Char] {
    def combine(x: Char, y: Char): Char = (x + y).toChar
    def empty: Char = 0
  }

  testChunk[Byte](byteChunkGenerator, "Bytes", "Byte")
  testChunk[Short](shortChunkGenerator, "Shorts", "Short")
  testChunk[Int](intChunkGenerator, "Ints", "Int")
  testChunk[Long](longChunkGenerator, "Longs", "Long")
  // Don't test traverse on Double or Float. They have naughty monoids.
  testChunk[Double](doubleChunkGenerator, "Doubles", "Double", false)
  testChunk[Float](floatChunkGenerator, "Floats", "Float", false)
  testChunk[Char](charChunkGenerator, "Unspecialized", "Char")

  testChunk[Byte](byteBufferChunkGenerator, "ByteBuffer", "Byte")
  testChunk[Byte](byteVectorChunkGenerator, "ByteVector", "Byte")
  testChunk[Short](shortBufferChunkGenerator, "ShortBuffer", "Short")
  testChunk[Int](intBufferChunkGenerator, "IntBuffer", "Int")
  testChunk[Long](longBufferChunkGenerator, "LongBuffer", "Long")
  testChunk[Double](doubleBufferChunkGenerator, "DoubleBuffer", "Double", false)
  testChunk[Float](floatBufferChunkGenerator, "FloatBuffer", "Float", false)
  testChunk[Char](charBufferChunkGenerator, "CharBuffer", "Char")
}
