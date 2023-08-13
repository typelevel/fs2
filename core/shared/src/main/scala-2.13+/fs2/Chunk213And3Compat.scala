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

import scala.collection.Factory
import scala.collection.immutable.ArraySeq
import scala.reflect.ClassTag

private[fs2] trait Chunk213And3Compat[+O] { self: Chunk[O] =>
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

  /** Views this Chunk as a Scala immutable Seq.
    * Contrary to all methods that start with _"to"_ (e.g. {{toVector}}, {{toArray}}),
    * this method does not copy data.
    */
  def asSeq: Seq[O] =
    new ChunkAsSeq(this)
}

private[fs2] final class ChunkAsSeq[+O](chunk: Chunk[O]) extends Seq[O] with Serializable {
  override def iterator: Iterator[O] =
    chunk.iterator

  override def apply(i: Int): O =
    chunk.apply(i)

  override def length: Int =
    chunk.size

  override def foreach[U](f: O => U): Unit =
    chunk.foreach { o => f(o); () }

  override def tapEach[U](f: O => U): Seq[O] = {
    chunk.foreach { o => f(o); () }
    this
  }

  override def isEmpty: Boolean =
    chunk.isEmpty

  override def copyToArray[B >: O](xs: Array[B], start: Int, len: Int): Int = {
    chunk.take(len).copyToArray(xs, start)
    math.min(len, xs.length - start)
  }

  override def headOption: Option[O] =
    chunk.head

  override def head: O =
    if (chunk.nonEmpty) chunk.apply(0)
    else throw new NoSuchElementException("head of empty Seq")

  override def lastOption: Option[O] =
    chunk.last

  override def last: O =
    if (chunk.nonEmpty) chunk.apply(chunk.size - 1)
    else throw new NoSuchElementException("tail of empty Seq")

  override def take(n: Int): Seq[O] =
    new ChunkAsSeq(chunk.take(n))

  override def takeRight(n: Int): Seq[O] =
    new ChunkAsSeq(chunk.takeRight(n))

  override def to[C1](factory: Factory[O, C1]): C1 =
    chunk.to(factory)

  override def toArray[B >: O: ClassTag]: Array[B] =
    chunk.toArray

  override def toList: List[O] =
    chunk.toList

  override def toVector: Vector[O] =
    chunk.toVector

  override def toString: String =
    chunk.toString

  override def zipWithIndex: Seq[(O, Int)] =
    new ChunkAsSeq(chunk.zipWithIndex)
}

private[fs2] trait ChunkCompanion213And3Compat { self: Chunk.type =>

  protected def platformIterable[O](i: Iterable[O]): Option[Chunk[O]] =
    i match {
      case a: ArraySeq[O] => Some(arraySeq(a))
      case _              => None
    }

  /** Creates a chunk backed by an immutable `ArraySeq`. */
  def arraySeq[O](arraySeq: ArraySeq[O]): Chunk[O] = {
    val arr = arraySeq.unsafeArray.asInstanceOf[Array[O]]
    array(arr)(ClassTag[O](arr.getClass.getComponentType))
  }

  /** Creates a chunk from a `scala.collection.IterableOnce`. */
  def iterableOnce[O](i: IterableOnce[O]): Chunk[O] =
    iterator(i.iterator)
}
