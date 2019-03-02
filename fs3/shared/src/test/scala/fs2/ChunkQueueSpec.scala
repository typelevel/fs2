package fs2

import org.scalatest.Succeeded

class ChunkQueueSpec extends Fs2Spec {
  "Chunk.Queue" - {
    "take" in {
      forAll { (chunks: List[Chunk[Int]], n: Int) =>
        val result = Chunk.Queue(chunks: _*).take(n)
        result.toChunk.toList shouldBe chunks.flatMap(_.toList).take(n)
        result.chunks.size should be <= chunks.size
      }
    }

    "drop" in {
      forAll { (chunks: List[Chunk[Int]], n: Int) =>
        val result = Chunk.Queue(chunks: _*).drop(n)
        result.toChunk.toList shouldBe chunks.flatMap(_.toList).drop(n)
        result.chunks.size should be <= chunks.size
      }
    }

    "takeRight" in {
      forAll { (chunks: List[Chunk[Int]], n: Int) =>
        val result = Chunk.Queue(chunks: _*).takeRight(n)
        result.toChunk.toList shouldBe chunks.flatMap(_.toList).takeRight(n)
        result.chunks.size should be <= chunks.size
      }
    }

    "dropRight" in {
      forAll { (chunks: List[Chunk[Int]], n: Int) =>
        val result = Chunk.Queue(chunks: _*).dropRight(n)
        result.toChunk.toList shouldBe chunks.flatMap(_.toList).dropRight(n)
        result.chunks.size should be <= chunks.size
      }
    }

    "equals" in {
      forAll { (chunks: List[Chunk[Int]], n: Int) =>
        val cq = Chunk.Queue(chunks: _*)
        cq shouldBe cq
        cq shouldBe Chunk.Queue(chunks: _*)
        if (cq.size > 1) cq.drop(1) should not be cq
        else Succeeded
      }
    }

    "hashCode" in {
      forAll { (chunks: List[Chunk[Int]], n: Int) =>
        val cq = Chunk.Queue(chunks: _*)
        cq.hashCode shouldBe cq.hashCode
        cq.hashCode shouldBe Chunk.Queue(chunks: _*).hashCode
        if (cq.size > 1) cq.drop(1).hashCode should not be cq.hashCode
        else Succeeded
      }
    }
  }
}
