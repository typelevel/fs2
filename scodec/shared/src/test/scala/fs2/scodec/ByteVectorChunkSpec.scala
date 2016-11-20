/* The arbitrary ByteVectors are taken from scodec.bits.Arbitraries */
package fs2
package scodec

import ArbitraryScodecBits._
import ChunkProps._

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
    "concat" in propConcat[Byte, ByteVectorChunk]
  }
}
