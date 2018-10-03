package fs2

import TestUtil._

class ChunkQueueSpec extends Fs2Spec {
  "ChunkQueue" - {
    "take" in {
      forAll { (chunks: List[Chunk[Int]], n: Int) =>
        val result = ChunkQueue(chunks: _*).take(n)
        result.toChunk.toList shouldBe chunks.flatMap(_.toList).take(n)
        result.chunks.size should be <= chunks.size
      }
    }

    "drop" in {
      forAll { (chunks: List[Chunk[Int]], n: Int) =>
        val result = ChunkQueue(chunks: _*).drop(n)
        result.toChunk.toList shouldBe chunks.flatMap(_.toList).drop(n)
        result.chunks.size should be <= chunks.size
      }
    }

    "takeRight" in {
      forAll { (chunks: List[Chunk[Int]], n: Int) =>
        val result = ChunkQueue(chunks: _*).takeRight(n)
        result.toChunk.toList shouldBe chunks.flatMap(_.toList).takeRight(n)
        result.chunks.size should be <= chunks.size
      }
    }

    "dropRight" in {
      forAll { (chunks: List[Chunk[Int]], n: Int) =>
        val result = ChunkQueue(chunks: _*).dropRight(n)
        result.toChunk.toList shouldBe chunks.flatMap(_.toList).dropRight(n)
        result.chunks.size should be <= chunks.size
      }
    }

    "equals" in {
      forAll { (chunks: List[Chunk[Int]], n: Int) =>
        val cq = ChunkQueue(chunks: _*)
        cq shouldBe cq
        cq shouldBe ChunkQueue(chunks: _*)
        if (cq.size > 1) cq.drop(1) should not be cq
      }
    }

    "hashCode" in {
      forAll { (chunks: List[Chunk[Int]], n: Int) =>
        val cq = ChunkQueue(chunks: _*)
        cq.hashCode shouldBe cq.hashCode
        cq.hashCode shouldBe ChunkQueue(chunks: _*).hashCode
        if (cq.size > 1) cq.drop(1).hashCode should not be cq.hashCode
      }
    }
  }
}