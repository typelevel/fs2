package fs2
package interop.scodec

import scala.reflect.ClassTag
import scodec.bits.ByteVector

final case class ByteVectorChunk(toByteVector: ByteVector)
    extends Chunk[Byte]
    with Chunk.KnownElementType[Byte] {
  def elementClassTag = ClassTag.Byte

  def apply(i: Int): Byte =
    toByteVector(i)

  def size: Int =
    toByteVector.size.toInt

  def copyToArray[O2 >: Byte](xs: Array[O2], start: Int): Unit =
    if (xs.isInstanceOf[Array[Byte]])
      toByteVector.copyToArray(xs.asInstanceOf[Array[Byte]], start)
    else toByteVector.toIndexedSeq.copyToArray(xs)

  override def drop(n: Int): Chunk[Byte] =
    if (n <= 0) this
    else if (n >= size) Chunk.empty
    else ByteVectorChunk(toByteVector.drop(n))

  override def take(n: Int): Chunk[Byte] =
    if (n <= 0) Chunk.empty
    else if (n >= size) this
    else ByteVectorChunk(toByteVector.take(n))

  protected def splitAtChunk_(n: Int): (Chunk[Byte], Chunk[Byte]) = {
    val (before, after) = toByteVector.splitAt(n)
    (ByteVectorChunk(before), ByteVectorChunk(after))
  }

  override def map[O2](f: Byte => O2): Chunk[O2] =
    Chunk.indexedSeq(toByteVector.toIndexedSeq.map(f))
}
