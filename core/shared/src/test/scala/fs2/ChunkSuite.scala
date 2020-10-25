/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2

import cats.Eq
import cats.kernel.CommutativeMonoid
import cats.kernel.laws.discipline.EqTests
import cats.laws.discipline.{AlternativeTests, MonadTests, TraverseFilterTests, TraverseTests}
import org.scalacheck.{Arbitrary, Cogen, Gen}
import org.scalacheck.Prop.forAll
import scala.reflect.ClassTag
import scala.util.control.NonFatal

class ChunkSuite extends Fs2Suite {
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters
      .withMinSuccessfulTests(if (isJVM) 100 else 25)
      .withWorkers(1)

  group("Chunk") {
    test("chunk-formation (1)") {
      assertEquals(Chunk.empty.toList,  List())
      assertEquals(Chunk.singleton(23).toList,  List(23))
    }

    property("chunk-formation (2)") {
      forAll { (c: Vector[Int]) =>
        assertEquals(Chunk.seq(c).toVector,  c)
        assertEquals(Chunk.seq(c).toList,  c.toList)
        assertEquals(Chunk.indexedSeq(c).toVector,  c)
        assertEquals(Chunk.indexedSeq(c).toList,  c.toList)
      }
    }

    test("Chunk.apply is optimized") {
      assert(Chunk(1).isInstanceOf[Chunk.Singleton[_]])
      assert(Chunk("Hello").isInstanceOf[Chunk.Singleton[_]])
      // Varargs on Scala.js use a scala.scalajs.js.WrappedArray, which
      // ends up falling through to the Chunk.indexedSeq constructor
      if (isJVM) {
        assert(Chunk(1, 2, 3).isInstanceOf[Chunk.Ints])
        assert(Chunk("Hello", "world").isInstanceOf[Chunk.Boxed[_]])
      }
    }

    test("Chunk.seq is optimized") {
      assert(Chunk.seq(List(1)).isInstanceOf[Chunk.Singleton[_]])
    }

    test("Array casts in Chunk.seq are safe") {
      val as = collection.mutable.ArraySeq[Int](0, 1, 2)
      val c = Chunk.seq(as)
      try assert(c.isInstanceOf[Chunk.Boxed[_]]) // 2.11/2.12
      catch {
        case NonFatal(_) => assert(c.isInstanceOf[Chunk.Ints]) // 2.13+
      }
    }
  }

  def testChunk[A: Arbitrary: ClassTag: CommutativeMonoid: Eq: Cogen](
      genChunk: Gen[Chunk[A]],
      name: String,
      of: String,
      testTraverse: Boolean = true
  ): Unit =
    group(s"$name") {
      implicit val implicitChunkArb: Arbitrary[Chunk[A]] = Arbitrary(genChunk)
      property("size")(forAll((c: Chunk[A]) => assertEquals(c.size,  c.toList.size)))
      property("take") {
        forAll { (c: Chunk[A], n: Int) =>
          assertEquals(c.take(n).toVector,  c.toVector.take(n))
        }
      }
      property("drop") {
        forAll { (c: Chunk[A], n: Int) =>
          assertEquals(c.drop(n).toVector,  c.toVector.drop(n))
        }
      }
      property("isEmpty") {
        forAll((c: Chunk[A]) => assertEquals(c.isEmpty,  c.toList.isEmpty))
      }
      property("toArray") {
        forAll { (c: Chunk[A]) =>
          assertEquals(c.toArray.toVector,  c.toVector)
          // Do it twice to make sure the first time didn't mutate state
          assertEquals(c.toArray.toVector,  c.toVector)
        }
      }
      property("copyToArray") {
        forAll { (c: Chunk[A]) =>
          val arr = new Array[A](c.size * 2)
          c.copyToArray(arr, 0)
          c.copyToArray(arr, c.size)
          assertEquals(arr.toVector,  (c.toVector ++ c.toVector))
        }
      }
      property("concat") {
        forAll { (c1: Chunk[A], c2: Chunk[A]) =>
          val result = Chunk
            .concat(List(Chunk.empty, c1, Chunk.empty, c2))
            .toVector
          assertEquals(result,  (c1.toVector ++ c2.toVector))
        }
      }
      test("concat empty") {
        assertEquals(Chunk.concat[A](List(Chunk.empty, Chunk.empty)),  Chunk.empty)
      }
      property("scanLeft") {
        forAll { (c: Chunk[A]) =>
          def step(acc: List[A], item: A) = acc :+ item
          assertEquals(c.scanLeft(List[A]())(step).toList,  (c.toList.scanLeft(List[A]())(step)))
        }
      }
      property("scanLeftCarry") {
        forAll { (c: Chunk[A]) =>
          def step(acc: List[A], item: A) = acc :+ item
          val listScan = c.toList.scanLeft(List[A]())(step)
          val (chunkScan, chunkCarry) = c.scanLeftCarry(List[A]())(step)

          assertEquals((chunkScan.toList, chunkCarry),  ((listScan.tail, listScan.last)))
        }
      }

      if (implicitly[ClassTag[A]] == ClassTag.Byte)
        property("toByteBuffer.byte") {
          forAll { (c: Chunk[A]) =>
            implicit val ev: A =:= Byte = null
            val arr = new Array[Byte](c.size)
            c.toByteBuffer.get(arr, 0, c.size)
            assertEquals(arr.toVector, c.toArray.toVector)
          }
        }

      checkAll(s"Eq[Chunk[$of]]", EqTests[Chunk[A]].eqv)
      checkAll("Monad[Chunk]", MonadTests[Chunk].monad[A, A, A])
      checkAll("Alternative[Chunk]", AlternativeTests[Chunk].alternative[A, A, A])
      checkAll("TraverseFilter[Chunk]", TraverseFilterTests[Chunk].traverseFilter[A, A, A])

      if (testTraverse)
        checkAll(s"Traverse[Chunk]", TraverseTests[Chunk].traverse[A, A, A, A, Option, Option])
    }

  implicit val commutativeMonoidForChar: CommutativeMonoid[Char] = new CommutativeMonoid[Char] {
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

  group("scanLeftCarry") {
    test("returns empty and zero for empty Chunk") {
      assertEquals(Chunk[Int]().scanLeftCarry(0)(_ + _),  ((Chunk.empty, 0)))
    }
    test("returns first result and first result for singleton") {
      assertEquals(Chunk(2).scanLeftCarry(1)(_ + _),  ((Chunk(3), 3)))
    }
    test("returns all results and last result for multiple elements") {
      assertEquals(Chunk(2, 3).scanLeftCarry(1)(_ + _),  ((Chunk(3, 6), 6)))
    }
  }

  group("concat primitives") {
    def testEmptyConcat[A](mkChunk: List[Chunk[A]] => Chunk[A]) =
      assertEquals(mkChunk(List(Chunk.empty, Chunk.empty)),  Chunk.empty)

    test("booleans")(testEmptyConcat(Chunk.concatBooleans))
    test("bytes")(testEmptyConcat(Chunk.concatBytes))
    test("floats")(testEmptyConcat(Chunk.concatFloats))
    test("doubles")(testEmptyConcat(Chunk.concatDoubles))
    test("shorts")(testEmptyConcat(Chunk.concatShorts))
    test("ints")(testEmptyConcat(Chunk.concatInts))
    test("longs")(testEmptyConcat(Chunk.concatLongs))
    test("chars")(testEmptyConcat(Chunk.concatChars))
  }

  test("map andThen toArray") {
    val arr: Array[Int] = Chunk(0, 0).map(identity).toArray
    assertEquals(arr.toList,  List(0, 0))
  }

  test("mapAccumulate andThen toArray") {
    val arr: Array[Int] = Chunk(0, 0).mapAccumulate(0)((s, o) => (s, o))._2.toArray
    assertEquals(arr.toList,  List(0, 0))
  }

  test("scanLeft andThen toArray") {
    val arr: Array[Int] = Chunk(0, 0).scanLeft(0)((_, o) => o).toArray
    assertEquals(arr.toList,  List(0, 0, 0))
  }

  test("zip andThen toArray") {
    val arr: Array[(Int, Int)] = Chunk(0, 0).zip(Chunk(0, 0)).toArray
    assertEquals(arr.toList,  List((0, 0), (0, 0)))
    val arr2: Array[Int] = Chunk(0, 0).zip(Chunk(0, 0)).map(_._1).toArray
    assertEquals(arr2.toList,  List(0, 0))
  }

  test("zipWithIndex andThen toArray") {
    forAll((chunk: Chunk[Int]) =>
      assertEquals(chunk.zipWithIndex.toList, chunk.toArray.zipWithIndex.toList)
    )
  }

  test("Boxed toArray - regression #1745") {
    Chunk.Boxed(Array[Any](0)).asInstanceOf[Chunk[Int]].toArray[Any]
    Chunk.Boxed(Array[Any](0)).asInstanceOf[Chunk[Int]].toArray[Int]
  }
}
