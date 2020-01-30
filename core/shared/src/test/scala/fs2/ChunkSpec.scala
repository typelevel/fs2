package fs2

import cats.Eq
import cats.kernel.CommutativeMonoid
import cats.kernel.laws.discipline.EqTests
import cats.laws.discipline.{AlternativeTests, MonadTests, TraverseFilterTests, TraverseTests}
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
      assert(Chunk.empty.toList == List())
      assert(Chunk.singleton(23).toList == List(23))
    }

    "chunk-formation (2)" in forAll { (c: Vector[Int]) =>
      assert(Chunk.seq(c).toVector == c)
      assert(Chunk.seq(c).toList == c.toList)
      assert(Chunk.indexedSeq(c).toVector == c)
      assert(Chunk.indexedSeq(c).toList == c.toList)
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
        case NonFatal(_) => c shouldBe a[Chunk.Ints] // 2.13+
      }
    }
  }

  def testChunk[A: Generator: ClassTag: CommutativeMonoid: Eq: Cogen](
      genChunk: Generator[Chunk[A]],
      name: String,
      of: String,
      testTraverse: Boolean = true
  ): Unit =
    s"$name" - {
      implicit val implicitChunkGenerator: Generator[Chunk[A]] = genChunk
      "size" in forAll { (c: Chunk[A]) =>
        assert(c.size == c.toList.size)
      }
      "take" in forAll { (c: Chunk[A], n: PosZInt) =>
        assert(c.take(n).toVector == c.toVector.take(n))
      }
      "drop" in forAll { (c: Chunk[A], n: PosZInt) =>
        assert(c.drop(n).toVector == c.toVector.drop(n))
      }
      "isEmpty" in forAll { (c: Chunk[A]) =>
        assert(c.isEmpty == c.toList.isEmpty)
      }
      "toArray" in forAll { c: Chunk[A] =>
        assert(c.toArray.toVector == c.toVector)
        // Do it twice to make sure the first time didn't mutate state
        assert(c.toArray.toVector == c.toVector)
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
      "concat empty" in {
        Chunk.concat[A](List(Chunk.empty, Chunk.empty)) shouldEqual Chunk.empty
      }
      "scanLeft" in forAll { c: Chunk[A] =>
        def step(acc: List[A], item: A) = acc :+ item
        assert(c.scanLeft(List[A]())(step).toList == (c.toList.scanLeft(List[A]())(step)))
      }
      "scanLeftCarry" in forAll { c: Chunk[A] =>
        def step(acc: List[A], item: A) = acc :+ item
        val listScan = c.toList.scanLeft(List[A]())(step)
        val (chunkScan, chunkCarry) = c.scanLeftCarry(List[A]())(step)

        (chunkScan.toList, chunkCarry) shouldBe ((listScan.tail, listScan.last))
      }

      if (implicitly[ClassTag[A]] == ClassTag.Byte)
        "toByteBuffer.byte" in forAll { c: Chunk[A] =>
          implicit val ev: A =:= Byte = null
          val arr = new Array[Byte](c.size)
          c.toByteBuffer.get(arr, 0, c.size)
          assert(arr.toVector == c.toArray.toVector)
        }

      import org.scalacheck.GeneratorCompat._

      checkAll(s"Eq[Chunk[$of]]", EqTests[Chunk[A]].eqv)
      checkAll("Monad[Chunk]", MonadTests[Chunk].monad[A, A, A])
      checkAll("Alternative[Chunk]", AlternativeTests[Chunk].alternative[A, A, A])
      checkAll("TraverseFilter[Chunk]", TraverseFilterTests[Chunk].traverseFilter[A, A, A])

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
  // Also, occasionally, a NaN value will show up in the generated functions, which then causes
  // laws tests to fail when comparing `Chunk(NaN)` to `Chunk(NaN)` -- for purposes of these tests
  // only, we redefine equality to consider two `NaN` values as equal.
  implicit val doubleEq: Eq[Double] = Eq.instance[Double]((x, y) => (x.isNaN && y.isNaN) || x == y)
  testChunk[Double](doubleChunkGenerator, "Doubles", "Double", false)
  implicit val floatEq: Eq[Float] = Eq.instance[Float]((x, y) => (x.isNaN && y.isNaN) || x == y)
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

  "scanLeftCarry" - {
    "returns empty and zero for empty Chunk" in {
      Chunk[Int]().scanLeftCarry(0)(_ + _) shouldBe ((Chunk.empty, 0))
    }
    "returns first result and first result for singleton" in {
      Chunk(2).scanLeftCarry(1)(_ + _) shouldBe ((Chunk(3), 3))
    }
    "returns all results and last result for multiple elements" in {
      Chunk(2, 3).scanLeftCarry(1)(_ + _) shouldBe ((Chunk(3, 6), 6))
    }
  }

  "concat primitives" - {
    def testEmptyConcat[A](mkChunk: List[Chunk[A]] => Chunk[A]) =
      mkChunk(List(Chunk.empty, Chunk.empty)) shouldEqual Chunk.empty

    "booleans" in testEmptyConcat(Chunk.concatBooleans)
    "bytes" in testEmptyConcat(Chunk.concatBytes)
    "floats" in testEmptyConcat(Chunk.concatFloats)
    "doubles" in testEmptyConcat(Chunk.concatDoubles)
    "shorts" in testEmptyConcat(Chunk.concatShorts)
    "ints" in testEmptyConcat(Chunk.concatInts)
    "longs" in testEmptyConcat(Chunk.concatLongs)
    "chars" in testEmptyConcat(Chunk.concatChars)
  }

  "map andThen toArray" in {
    val arr: Array[Int] = Chunk(0, 0).map(identity).toArray
    arr shouldBe Array(0, 0)
  }

  "mapAccumulate andThen toArray" in {
    val arr: Array[Int] = Chunk(0, 0).mapAccumulate(0)((s, o) => (s, o))._2.toArray
    arr shouldBe Array(0, 0)
  }

  "scanLeft andThen toArray" in {
    val arr: Array[Int] = Chunk(0, 0).scanLeft(0)((_, o) => o).toArray
    arr shouldBe Array(0, 0, 0)
  }

  "zip andThen toArray" in {
    val arr: Array[(Int, Int)] = Chunk(0, 0).zip(Chunk(0, 0)).toArray
    arr shouldBe Array((0, 0), (0, 0))
    val arr2: Array[Int] = Chunk(0, 0).zip(Chunk(0, 0)).map(_._1).toArray
    arr2 shouldBe Array(0, 0)
  }

  "Boxed toArray - regression #1745" in {
    Chunk.Boxed(Array[Any](0)).asInstanceOf[Chunk[Int]].toArray[Any]
    Chunk.Boxed(Array[Any](0)).asInstanceOf[Chunk[Int]].toArray[Int]
    Succeeded
  }
}
