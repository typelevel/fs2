package fs2
package interop.scodec

import scodec.bits.ByteVector

final class ByteVectorChunk private (val toByteVector: ByteVector) extends Chunk[Byte] {
  def apply(i: Int): Byte =
    toByteVector(i)

  def size: Int =
    toByteVector.size.toInt

  protected def splitAtChunk_(n: Int): (Chunk[Byte], Chunk[Byte]) = {
    val (before,after) = toByteVector.splitAt(n)
    (ByteVectorChunk(before), ByteVectorChunk(after))
  }
}

object ByteVectorChunk {
  def apply(bv: ByteVector): ByteVectorChunk = new ByteVectorChunk(bv)
}
