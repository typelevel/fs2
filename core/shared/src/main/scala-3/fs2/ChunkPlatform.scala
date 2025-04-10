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

import scodec.bits.ByteVector

import scala.collection.immutable.ArraySeq
import scala.collection.immutable
import scala.reflect.ClassTag

private[fs2] trait ChunkPlatform[+O] extends Chunk213And3Compat[O] {
  self: Chunk[O] =>

  def toIArray[O2 >: O: ClassTag]: IArray[O2] = IArray.unsafeFromArray(toArray)

  def toIArraySlice[O2 >: O](implicit ct: ClassTag[O2]): Chunk.IArraySlice[O2] =
    this match {
      case as: Chunk.IArraySlice[_] if ct.wrap.runtimeClass eq as.getClass =>
        as.asInstanceOf[Chunk.IArraySlice[O2]]
      case _ => new Chunk.IArraySlice(IArray.unsafeFromArray(toArray(ct)), 0, size)
    }

  private[fs2] def asSeqPlatform: Option[IndexedSeq[O]] =
    this match {
      case arraySlice: Chunk.ArraySlice[_] =>
        Some(
          ArraySeq
            .unsafeWrapArray(arraySlice.values)
            .slice(
              from = arraySlice.offset,
              until = arraySlice.offset + arraySlice.length
            )
        )

      case iArraySlice: Chunk.IArraySlice[_] =>
        Some(
          ArraySeq
            .unsafeWrapArray(
              IArray.genericWrapArray(iArraySlice.values).toArray(iArraySlice.ct)
            )
            .slice(
              from = iArraySlice.offset,
              until = iArraySlice.offset + iArraySlice.length
            )
        )

      case _ =>
        None
    }
}

private[fs2] trait ChunkAsSeqPlatform[+O] extends ChunkAsSeq213And3Compat[O] {
  self: ChunkAsSeq[O] =>
}

private[fs2] trait ChunkCompanionPlatform extends ChunkCompanion213And3Compat {
  self: Chunk.type =>

  /** Creates a chunk backed by an immutable array. */
  def iarray[O: ClassTag](arr: IArray[O]): Chunk[O] = new IArraySlice(arr, 0, arr.length)

  /** Creates a chunk backed by a slice of an immutable array. */
  def iarray[O: ClassTag](arr: IArray[O], offset: Int, length: Int): Chunk[O] =
    new IArraySlice(arr, offset, length)

  case class IArraySlice[O](values: IArray[O], offset: Int, length: Int)(implicit
      private[fs2] val ct: ClassTag[O]
  ) extends Chunk[O] {
    require(
      offset >= 0 && offset <= values.size && length >= 0 && length <= values.size && offset + length <= values.size,
      "IArraySlice out of bounds"
    )

    def size = length
    def apply(i: Int) = values(offset + i)

    def copyToArray[O2 >: O](xs: Array[O2], start: Int): Unit =
      if (xs.getClass eq ct.wrap.runtimeClass) System.arraycopy(values, offset, xs, start, length)
      else {
        values.iterator.slice(offset, offset + length).copyToArray(xs, start)
        ()
      }

    override def drop(n: Int): Chunk[O] =
      if (n <= 0) this
      else if (n >= size) Chunk.empty
      else IArraySlice(values, offset + n, length - n)

    override def take(n: Int): Chunk[O] =
      if (n <= 0) Chunk.empty
      else if (n >= size) this
      else IArraySlice(values, offset, n)

    protected def splitAtChunk_(n: Int): (Chunk[O], Chunk[O]) =
      IArraySlice(values, offset, n) -> IArraySlice(values, offset + n, length - n)

    override def toArray[O2 >: O: ClassTag]: Array[O2] =
      values.slice(offset, offset + length).asInstanceOf[Array[O2]]

    override def toIArray[O2 >: O: ClassTag]: IArray[O2] =
      if (offset == 0 && length == values.length) values
      else super.toIArray[O2]

    override def toByteVector[B >: O](implicit ev: B =:= Byte): ByteVector =
      if (values.isInstanceOf[Array[Byte]])
        ByteVector.view(values.asInstanceOf[Array[Byte]], offset, length)
      else ByteVector.viewAt(i => apply(i.toInt), size.toLong)
  }
}
