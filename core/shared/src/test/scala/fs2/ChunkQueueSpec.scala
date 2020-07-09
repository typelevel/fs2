package fs2

import org.scalatest.Succeeded

class ChunkQueueSpec extends Fs2Spec {
  "Chunk.Queue" - {
    "startsWith matches List implementation prefixes" in {
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

    "take" in {
      forAll { (chunks: List[Chunk[Int]], n: Int) =>
        val result = Chunk.Queue(chunks: _*).take(n)
        assert(result.toChunk.toList == chunks.flatMap(_.toList).take(n))
        assert(result.chunks.size <= chunks.size)
      }
    }

    "drop" in {
      forAll { (chunks: List[Chunk[Int]], n: Int) =>
        val result = Chunk.Queue(chunks: _*).drop(n)
        assert(result.toChunk.toList == chunks.flatMap(_.toList).drop(n))
        assert(result.chunks.size <= chunks.size)
      }
    }

    "takeRight" in {
      forAll { (chunks: List[Chunk[Int]], n: Int) =>
        val result = Chunk.Queue(chunks: _*).takeRight(n)
        assert(result.toChunk.toList == chunks.flatMap(_.toList).takeRight(n))
        assert(result.chunks.size <= chunks.size)
      }
    }

    "dropRight" in {
      forAll { (chunks: List[Chunk[Int]], n: Int) =>
        val result = Chunk.Queue(chunks: _*).dropRight(n)
        assert(result.toChunk.toList == chunks.flatMap(_.toList).dropRight(n))
        assert(result.chunks.size <= chunks.size)
      }
    }

    "equals" in {
      forAll { (chunks: List[Chunk[Int]]) =>
        val cq = Chunk.Queue(chunks: _*)
        assert(cq == cq)
        assert(cq == Chunk.Queue(chunks: _*))
        if (cq.size > 1) assert(cq.drop(1) != cq)
        else Succeeded
      }
    }

    "hashCode" in {
      forAll { (chunks: List[Chunk[Int]]) =>
        val cq = Chunk.Queue(chunks: _*)
        assert(cq.hashCode == cq.hashCode)
        assert(cq.hashCode == Chunk.Queue(chunks: _*).hashCode)
        if (cq.size > 1) assert(cq.drop(1).hashCode != cq.hashCode)
        else Succeeded
      }
    }
  }
}
