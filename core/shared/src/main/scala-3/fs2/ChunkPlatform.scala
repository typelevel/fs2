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

import scala.collection.immutable.ArraySeq
import scala.collection.immutable
import scala.collection.mutable.ArrayBuilder
import scala.reflect.ClassTag

private[fs2] trait ChunkPlatform[+O] { self: Chunk[O] =>

  def toArraySeq[O2 >: O: ClassTag]: ArraySeq[O2] = {
    val array: Array[O2] = new Array[O2](size)
    copyToArray(array)
    ArraySeq.unsafeWrapArray[O2](array)
  }

  def toArraySeqUntagged: ArraySeq[O] = {
    val buf = ArraySeq.untagged.newBuilder[O]
    buf.sizeHint(size)
    var i = 0
    while (i < size) {
      buf += apply(i)
      i += 1
    }
    buf.result()
  }

  def toIArray[O2 >: O: ClassTag]: IArray[O2] = IArray.unsafeFromArray(toArray)

  def toIArraySlice[O2 >: O](implicit ct: ClassTag[O2]): Chunk.IArraySlice[O2] =
    this match {
      case as: Chunk.IArraySlice[_] if ct.wrap.runtimeClass eq as.getClass =>
        as.asInstanceOf[Chunk.IArraySlice[O2]]
      case _ => new Chunk.IArraySlice(IArray.unsafeFromArray(toArray(ct)), 0, size)
    }
}

private[fs2] trait ChunkCompanionPlatform { self: Chunk.type =>

  protected def platformIterable[O](i: Iterable[O]): Option[Chunk[O]] =
    i match {
      case a: immutable.ArraySeq[O] => Some(arraySeq(a))
      case _                        => None
    }

  private[fs2] def makeArrayBuilder[A](implicit ct: ClassTag[A]): ArrayBuilder[A] =
    ArrayBuilder.make(ct)

  /** Creates a chunk backed by an immutable `ArraySeq`.
    */
  def arraySeq[O](arraySeq: immutable.ArraySeq[O]): Chunk[O] = {
    val arr = arraySeq.unsafeArray.asInstanceOf[Array[O]]
    array(arr)(ClassTag[O](arr.getClass.getComponentType))
  }

  /** Creates a chunk backed by an immutable array.
    */
  def iarray[O: ClassTag](arr: IArray[O]): Chunk[O] = new IArraySlice(arr, 0, arr.length)

  /** Creates a chunk backed by a slice of an immutable array.
    */
  def iarray[O: ClassTag](arr: IArray[O], offset: Int, length: Int): Chunk[O] =
    new IArraySlice(arr, offset, length)

  case class IArraySlice[O](values: IArray[O], offset: Int, length: Int)(implicit ct: ClassTag[O])
      extends Chunk[O] {
    require(
      offset >= 0 && offset <= values.size && length >= 0 && length <= values.size && offset + length <= values.size
    )

    def size = length
    def apply(i: Int) = values(offset + i)

    def copyToArray[O2 >: O](xs: Array[O2], start: Int): Unit =
      if (xs.getClass eq ct.wrap.runtimeClass)
        System.arraycopy(values, offset, xs, start, length)
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
  }

  /** Creates a chunk from a `scala.collection.IterableOnce`. */
  def iterableOnce[O](i: collection.IterableOnce[O]): Chunk[O] =
    iterator(i.iterator)
}
