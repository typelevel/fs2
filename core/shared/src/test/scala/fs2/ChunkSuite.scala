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
import org.scalacheck.{Arbitrary, Cogen, Gen, Test}
import org.scalacheck.Prop.forAll
import scodec.bits.ByteVector

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.util.concurrent.atomic.AtomicInteger
import scala.reflect.ClassTag

class ChunkSuite extends Fs2Suite {
  override def scalaCheckTestParameters: Test.Parameters =
    super.scalaCheckTestParameters
      .withMinSuccessfulTests(if (isJVM) 100 else 25)
      .withWorkers(1)

  group("Chunk") {
    test("chunk-formation (1)") {
      assertEquals(Chunk.empty.toList, List())
      assertEquals(Chunk.singleton(23).toList, List(23))
    }

    property("chunk-formation (2)") {
      forAll { (v: Vector[Int]) =>
        assertEquals(Chunk.from(v).toVector, v)
        assertEquals(Chunk.from(v).toList, v.toList)
      }
    }

    test("Chunk.apply is optimized") {
      assert(Chunk(1).isInstanceOf[Chunk.Singleton[_]])
      assert(Chunk("Hello").isInstanceOf[Chunk.Singleton[_]])
      // Varargs on Scala.js use a scala.scalajs.js.WrappedArray, which
      // ends up falling through to the Chunk.indexedSeq constructor
      if (isJVM) {
        assert(Chunk(1, 2, 3).isInstanceOf[Chunk.ArraySlice[_]])
        assert(Chunk("Hello", "world").isInstanceOf[Chunk.ArraySlice[_]])
      }
    }

    test("Chunk.from is optimized") {
      assert(Chunk.from(List(1)).isInstanceOf[Chunk.Singleton[_]])
      assert(Chunk.from(Vector(1)).isInstanceOf[Chunk.Singleton[_]])
    }

    test("Array casts in Chunk.from are safe") {
      val as = collection.mutable.ArraySeq[Int](0, 1, 2)
      val c = Chunk.from(as)
      assert(c.isInstanceOf[Chunk.ArraySlice[_]])
    }

    test("Chunk.asSeq roundtrip") {
      forAll { (c: Chunk[Int]) =>
        // Chunk -> Seq -> Chunk
        val seq = c.asSeq
        val result = Chunk.from(seq)

        // Check data consistency.
        assertEquals(result, c)

        // Check unwrap.
        if (seq.isInstanceOf[ChunkAsSeq[_]]) {
          assert(result eq c)
        }
      } && forAll { (e: Either[Seq[Int], Vector[Int]]) =>
        // Seq -> Chunk -> Seq
        val seq = e.merge
        val chunk = Chunk.from(seq)
        val result = chunk.asSeq

        // Check data consistency.
        assertEquals(result, seq)

        // Check unwrap.
        if (seq.isInstanceOf[Vector[_]] && chunk.size >= 2) {
          assert(result eq seq)
        }
      }
    }

    test("Chunk.javaList unwraps asJava") {
      forAll { (c: Chunk[Int]) =>
        assert(Chunk.javaList(c.asJava) eq c)
      }
    }

    test("Chunk.collect behaves as filter + map") {
      forAll { (c: Chunk[Int]) =>
        val extractor = new OddStringExtractor
        val pf: PartialFunction[Int, String] = { case extractor(s) => s }

        val result = c.collect(pf)

        assertEquals(result, c.filter(pf.isDefinedAt).map(pf))
      }
    }

    test("Chunk.collect evaluates pattern matchers once per item") {
      forAll { (c: Chunk[Int]) =>
        val extractor = new OddStringExtractor

        val _ = c.collect { case extractor(s) => s }

        assertEquals(extractor.callCounter.get(), c.size)
      }
    }

    class OddStringExtractor {
      val callCounter: AtomicInteger = new AtomicInteger(0)

      def unapply(i: Int): Option[String] = {
        callCounter.incrementAndGet()
        if (i % 2 != 0) Some(i.toString) else None
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
      property("size") {
        forAll { (c: Chunk[A]) =>
          assertEquals(c.size, c.toList.size)
        }
      }
      property("take") {
        forAll { (c: Chunk[A], n: Int) =>
          assertEquals(c.take(n).toVector, c.toVector.take(n))
        }
      }
      property("drop") {
        forAll { (c: Chunk[A], n: Int) =>
          assertEquals(c.drop(n).toVector, c.toVector.drop(n))
        }
      }
      property("isEmpty") {
        forAll((c: Chunk[A]) => assertEquals(c.isEmpty, c.toList.isEmpty))
      }
      property("toArray") {
        forAll { (c: Chunk[A]) =>
          assertEquals(c.toArray.toVector, c.toVector)
          // Do it twice to make sure the first time didn't mutate state
          assertEquals(c.toArray.toVector, c.toVector)
        }
      }
      property("copyToArray") {
        forAll { (c: Chunk[A]) =>
          val arr = new Array[A](c.size * 2)
          c.copyToArray(arr, 0)
          c.copyToArray(arr, c.size)
          assertEquals(arr.toVector, c.toVector ++ c.toVector)
        }
      }
      property("concat") {
        forAll { (c1: Chunk[A], c2: Chunk[A]) =>
          val result = Chunk
            .concat(List(Chunk.empty, c1, Chunk.empty, c2))
            .toVector
          assertEquals(result, c1.toVector ++ c2.toVector)
        }
      }
      test("concat empty") {
        assertEquals(Chunk.concat[A](List(Chunk.empty, Chunk.empty)), Chunk.empty)
      }
      property("scanLeft") {
        forAll { (c: Chunk[A]) =>
          def step(acc: List[A], item: A) = acc :+ item
          assertEquals(c.scanLeft(List[A]())(step).toList, c.toList.scanLeft(List[A]())(step))
        }
      }
      property("scanLeftCarry") {
        forAll { (c: Chunk[A]) =>
          def step(acc: List[A], item: A) = acc :+ item
          val listScan = c.toList.scanLeft(List[A]())(step)
          val (chunkScan, chunkCarry) = c.scanLeftCarry(List[A]())(step)

          assertEquals((chunkScan.toList, chunkCarry), ((listScan.tail, listScan.last)))
        }
      }
      property("asSeq") {
        forAll { (c: Chunk[A]) =>
          val s = c.asSeq
          val v = c.toVector
          val l = c.toList

          // Equality.
          assertEquals(s, v)
          assertEquals(s, l: Seq[A])

          // Hashcode.
          assertEquals(s.hashCode, v.hashCode)
          assertEquals(s.hashCode, l.hashCode)

          // Copy to array.
          assertEquals(s.toArray.toVector, v.toArray.toVector)
          assertEquals(s.toArray.toVector, l.toArray.toVector)
        }
      }
      property("asJava") {
        forAll { (c: Chunk[A]) =>
          val view = c.asJava
          val copy = java.util.Arrays.asList(c.toVector: _*)

          // Equality.
          assertEquals(view, copy)

          // Hashcode.
          assertEquals(view.hashCode, copy.hashCode)

          // Copy to array (untyped).
          assertEquals(view.toArray.toVector, copy.toArray.toVector)

          // Copy to array (typed).
          val hint = Array.emptyObjectArray.asInstanceOf[Array[A with Object]]
          val viewArray = view.toArray(hint)
          val copyArray = copy.toArray(hint)
          val viewVector: Vector[A] = viewArray.toVector
          val copyVector: Vector[A] = copyArray.toVector
          assertEquals(viewVector, copyVector)
          assertEquals(viewVector, c.toVector)
        }
      }

      if (implicitly[ClassTag[A]] == ClassTag.Byte) {
        property("toByteBuffer.byte") {
          forAll { (c: Chunk[A]) =>
            implicit val ev: A =:= Byte = null
            val arr = new Array[Byte](c.size)
            c.toByteBuffer.get(arr, 0, c.size)
            // fails to use `ev` to infer, so we resort to the untyped check
            assertEquals[Any, Any](arr.toVector, c.toArray.toVector)
          }
        }

        property("toByteVector") {
          forAll { (c: Chunk[A]) =>
            implicit val ev: A =:= Byte = null
            assertEquals[Any, Any](c.toByteVector.toArray.toVector, c.toArray.toVector)
          }
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

  testChunk[Int](intChunkGenerator, "Ints", "Int")

  testChunk[Byte](byteBufferChunkGenerator, "ByteBuffer", "Byte")
  testChunk[Byte](byteVectorChunkGenerator, "ByteVector", "Byte")
  testChunk[Char](charBufferChunkGenerator, "CharBuffer", "Char")

  group("scanLeftCarry") {
    test("returns empty and zero for empty Chunk") {
      assertEquals(Chunk[Int]().scanLeftCarry(0)(_ + _), ((Chunk.empty, 0)))
    }
    test("returns first result and first result for singleton") {
      assertEquals(Chunk(2).scanLeftCarry(1)(_ + _), ((Chunk(3), 3)))
    }
    test("returns all results and last result for multiple elements") {
      assertEquals(Chunk(2, 3).scanLeftCarry(1)(_ + _), ((Chunk(3, 6), 6)))
    }
  }

  test("map andThen toArray") {
    val arr: Array[Int] = Chunk(0, 0).map(identity).toArray
    assertEquals(arr.toList, List(0, 0))
  }

  test("mapAccumulate andThen toArray") {
    val arr: Array[Int] = Chunk(0, 0).mapAccumulate(0)((s, o) => (s, o))._2.toArray
    assertEquals(arr.toList, List(0, 0))
  }

  test("scanLeft andThen toArray") {
    val arr: Array[Int] = Chunk(0, 0).scanLeft(0)((_, o) => o).toArray
    assertEquals(arr.toList, List(0, 0, 0))
  }

  test("zip andThen toArray") {
    val arr: Array[(Int, Int)] = Chunk(0, 0).zip(Chunk(0, 0)).toArray
    assertEquals(arr.toList, List((0, 0), (0, 0)))
    val arr2: Array[Int] = Chunk(0, 0).zip(Chunk(0, 0)).map(_._1).toArray
    assertEquals(arr2.toList, List(0, 0))
  }

  test("zipWithIndex andThen toArray") {
    forAll((chunk: Chunk[Int]) =>
      assertEquals(chunk.zipWithIndex.toList, chunk.toArray.zipWithIndex.toList)
    )
  }

  test("ArraySlice toArray - regression #1745") {
    Chunk.ArraySlice(Array[Any](0)).asInstanceOf[Chunk[Int]].toArray[Any]
    Chunk.ArraySlice(Array[Any](0)).asInstanceOf[Chunk[Int]].toArray[Int]
  }

  test("ArraySlice toByteVector") {
    Chunk.ArraySlice(Array[Any](0.toByte)).asInstanceOf[Chunk[Byte]].toByteVector
  }

  test("toArraySlice does not copy when chunk is already an ArraySlice instance") {
    val chunk: Chunk[Int] = Chunk.ArraySlice(Array(0))
    assert(chunk eq chunk.toArraySlice)
    val chunk2: Chunk[Any] = Chunk.ArraySlice(Array(new Object))
    assert(chunk2 eq chunk2.toArraySlice)
  }

  test("toArraySlice does not copy when chunk is an array-backed bytevector") {
    val arr = Array[Byte](0, 1, 2, 3)
    val chunk: Chunk[Byte] = Chunk.byteVector(ByteVector.view(arr))
    assert(chunk.toArraySlice.values eq arr)
  }

  test("ByteVectorChunk#toArraySlice does not throw class cast exception") {
    val chunk: Chunk[Byte] = Chunk.byteVector(ByteVector.view(Array[Byte](0, 1, 2, 3)))
    assertEquals(chunk.toArraySlice[Any].values.apply(0), 0)
  }

  test("toArraySlice does not copy when chunk is an array-backed bytebuffer") {
    val bb = ByteBuffer.allocate(4)
    val chunk: Chunk[Byte] = Chunk.byteBuffer(bb)
    assert(chunk.toArraySlice.values eq bb.array)
  }

  test("ByteBuffer#toArraySlice does not throw class cast exception") {
    val chunk: Chunk[Byte] = Chunk.byteBuffer(ByteBuffer.allocate(4))
    assertEquals(chunk.toArraySlice[Any].values.apply(0), 0)
  }

  test("toArraySlice does not copy when chunk is an array-backed charbuffer") {
    val cb = CharBuffer.allocate(4)
    val chunk: Chunk[Char] = Chunk.charBuffer(cb)
    assert(chunk.toArraySlice.values eq cb.array)
  }

  test("CharBuffer#toArraySlice does not throw class cast exception") {
    val chunk: Chunk[Char] = Chunk.charBuffer(CharBuffer.allocate(4))
    assertEquals(chunk.toArraySlice[Any].values.apply(0), 0)
  }

  test("compactUntagged - regression #2679") {
    Chunk.Queue.singleton(Chunk.singleton(1)).traverse(x => Option(x))
  }
}
