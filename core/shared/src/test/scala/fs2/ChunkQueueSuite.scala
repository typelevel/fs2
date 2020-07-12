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
      assert(computed == viaTake)
      // here is another way to express the law:
      assert(computed == queue.startsWith(items))
    }
  }
}
