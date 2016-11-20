/* The arbitrary ByteVectors are taken from scodec.bits.Arbitraries */
package fs2
package interop.scodec

import ArbitraryScodecBits._
import ChunkProps._

import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Arbitrary.arbitrary

class ByteVectorChunkSpec extends Fs2Spec
{
  "ByteVectorChunk" - {
    "size" in propSize[Byte, ByteVectorChunk]
    "take" in propTake[Byte, ByteVectorChunk]
    "drop" in propDrop[Byte, ByteVectorChunk]
    "uncons" in propUncons[Byte, ByteVectorChunk]
    "isEmpty" in propIsEmpty[Byte, ByteVectorChunk]
    "filter" in propFilter[Byte, ByteVectorChunk]
    "foldLeft" in propFoldLeft[Byte, ByteVectorChunk]
    "foldRight" in propFoldRight[Byte, ByteVectorChunk]
    "toArray" in propToArray[Byte, ByteVectorChunk]

    val genChunkBytes: Gen[Chunk[Byte]] =
      for {
        n <- Gen.choose(0, 100)
        values <- Gen.containerOfN[Array, Byte](n, Arbitrary.arbByte.arbitrary)
        offset <- Gen.choose(0, n)
        sz <- Gen.choose(0, n - offset)
      } yield Chunk.bytes(values, offset, sz)

    implicit val arbByteConformantChunks: Arbitrary[Chunk[Byte]] =
      Arbitrary(Gen.oneOf(genChunkBytes, arbitrary[ByteVectorChunk], Gen.const(Chunk.empty)))

    "concat with Byte-conformant chunks" in forAll { (c: ByteVectorChunk, cs: List[Chunk[Byte]]) =>
      val result = Chunk.concat(c :: cs)
      result.toVector shouldBe (c :: cs).foldLeft(Vector.empty[Byte])(_ ++ _.toVector)
      if (result.isEmpty) result shouldBe a[ByteVectorChunk]
    }
  }
}
