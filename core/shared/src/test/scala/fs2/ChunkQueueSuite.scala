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

import org.scalacheck.Prop.forAll

class ChunkQueueSuite extends Fs2Suite {
  test("take") {
    forAll { (chunks: List[Chunk[Int]], n: Int) =>
      val result = Chunk.Queue(chunks: _*).take(n)
      assertEquals(result.toChunk.toList, chunks.flatMap(_.toList).take(n))
      assert(result.chunks.size <= chunks.size)
    }
  }

  test("drop") {
    forAll { (chunks: List[Chunk[Int]], n: Int) =>
      val result = Chunk.Queue(chunks: _*).drop(n)
      assertEquals(result.toChunk.toList, chunks.flatMap(_.toList).drop(n))
      assert(result.chunks.size <= chunks.size)
    }
  }

  test("takeRight") {
    forAll { (chunks: List[Chunk[Int]], n: Int) =>
      val result = Chunk.Queue(chunks: _*).takeRight(n)
      assertEquals(result.toChunk.toList, chunks.flatMap(_.toList).takeRight(n))
      assert(result.chunks.size <= chunks.size)
    }
  }

  test("dropRight") {
    forAll { (chunks: List[Chunk[Int]], n: Int) =>
      val result = Chunk.Queue(chunks: _*).dropRight(n)
      assertEquals(result.toChunk.toList, chunks.flatMap(_.toList).dropRight(n))
      assert(result.chunks.size <= chunks.size)
    }
  }

  test("equals") {
    forAll { (chunks: List[Chunk[Int]]) =>
      val cq = Chunk.Queue(chunks: _*)
      assertEquals(cq, cq)
      assertEquals(cq, Chunk.Queue(chunks: _*))
      if (cq.size > 1) assert(cq.drop(1) != cq)
    }
  }

  test("hashCode") {
    forAll { (chunks: List[Chunk[Int]]) =>
      val cq = Chunk.Queue(chunks: _*)
      assertEquals(cq.hashCode, cq.hashCode)
      assertEquals(cq.hashCode, Chunk.Queue(chunks: _*).hashCode)
      if (cq.size > 1) assert(cq.drop(1).hashCode != cq.hashCode)
    }
  }

  test("startsWith matches List implementation prefixes") {
    forAll { (chunks: List[Chunk[Int]], items: List[Int]) =>
      val queue = Chunk.Queue(chunks: _*)
      val flattened: List[Int] = chunks.flatMap(_.toList)
      val prefixes = (0 until flattened.size).map(flattened.take(_))

      prefixes.foreach { prefix =>
        assert(queue.startsWith(prefix))
      }

      val viaTake = queue.take(items.size).toChunk == Chunk.seq(items)
      val computed = flattened.startsWith(items)
      assertEquals(computed, viaTake)
      // here is another way to express the law:
      assertEquals(computed, queue.startsWith(items))
    }
  }
}
