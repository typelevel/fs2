package fs2
package scodec

import scala.reflect.{ClassTag, classTag}
import _root_.scodec.bits.ByteVector

final class ByteVectorChunk private (val toByteVector: ByteVector)
    extends MonomorphicChunk[Byte]
{
  def apply(i: Int): Byte =
    toByteVector(i)

  def copyToArray[B >: Byte](xs: Array[B], start: Int): Unit =
    xs match {
      case byteArray: Array[Byte] =>
        toByteVector.copyToArray(byteArray, start)
      case _ =>
        toByteVector.toArray.iterator.copyToArray(xs, start)
    }

  def drop(n: Int): Chunk[Byte] =
    ByteVectorChunk(toByteVector.drop(n))

  def filter(f: Byte => Boolean): Chunk[Byte] = {
    var i = 0
    val bound = toByteVector.size

    val values2 = new Array[Byte](size)
    var size2 = 0

    while (i < bound) {
      val b = toByteVector(i)
      if (f(b)) {
        values2(size2) = toByteVector(i)
        size2 += 1
      }

      i += 1
    }

    ByteVectorChunk(ByteVector.view(values2, 0, size2))
  }

  def foldLeft[B](z: B)(f: (B, Byte) => B): B =
    toByteVector.foldLeft(z)(f)

  def foldRight[B](z: B)(f: (Byte, B) => B): B =
    toByteVector.foldRight(z)(f)

  def size: Int =
    toByteVector.size.toInt

  def take(n: Int): Chunk[Byte] =
    ByteVectorChunk(toByteVector.take(n))

  protected val tag: ClassTag[_] =
    classTag[Byte]

  override def concatAll[B >: Byte](chunks: Seq[Chunk[B]]): Chunk[B] = {
    val conformed = chunks collect { case toByteVectorc: ByteVectorChunk => toByteVectorc }
    if (chunks.isEmpty) {
      this
    } else if ((chunks lengthCompare conformed.size) == 0) {
      ByteVectorChunk(conformed.foldLeft(toByteVector)(_ ++ _.toByteVector))
    } else {
      super.concatAll(chunks)
    }
  }
}

object ByteVectorChunk {
  def apply(bv: ByteVector): ByteVectorChunk =
    new ByteVectorChunk(bv)
}
