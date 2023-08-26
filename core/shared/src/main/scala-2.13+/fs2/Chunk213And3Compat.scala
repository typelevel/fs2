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

import scala.collection.{Iterable => GIterable, Factory, SeqFactory}
import scala.collection.immutable.ArraySeq
import scala.reflect.ClassTag

private[fs2] trait Chunk213And3Compat[+O] {
  self: Chunk[O] =>

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
}

private[fs2] trait ChunkAsSeq213And3Compat[+O] {
  self: ChunkAsSeq[O] =>

  override def knownSize: Int =
    chunk.size

  override def copyToArray[O2 >: O](xs: Array[O2], start: Int, len: Int): Int = {
    chunk.take(len).copyToArray(xs, start)
    math.min(len, xs.length - start)
  }

  override def map[O2](f: O => O2): IndexedSeq[O2] =
    new ChunkAsSeq(chunk.map(f))

  override def zipWithIndex: IndexedSeq[(O, Int)] =
    new ChunkAsSeq(chunk.zipWithIndex)

  override def tapEach[U](f: O => U): IndexedSeq[O] = {
    chunk.foreach { o => f(o); () }
    this
  }

  override def to[C1](factory: Factory[O, C1]): C1 =
    chunk.to(factory)

  override val iterableFactory: SeqFactory[IndexedSeq] =
    ArraySeq.untagged

  override lazy val empty: IndexedSeq[O] =
    new ChunkAsSeq(Chunk.empty)
}

private[fs2] trait ChunkCompanion213And3Compat {
  self: Chunk.type =>

  protected def platformFrom[O](i: GIterable[O]): Option[Chunk[O]] =
    i match {
      case arraySeq: ArraySeq[O] =>
        val arr = arraySeq.unsafeArray.asInstanceOf[Array[O]]
        Some(array(arr)(ClassTag[O](arr.getClass.getComponentType)))

      case _ =>
        None
    }

  /** Creates a chunk backed by an immutable `ArraySeq`. */
  @deprecated(
    "Use the `from` general factory instead",
    "3.8.1"
  )
  def arraySeq[O](arraySeq: ArraySeq[O]): Chunk[O] =
    from(arraySeq)

  /** Creates a chunk from a `scala.collection.IterableOnce`. */
  def iterableOnce[O](i: IterableOnce[O]): Chunk[O] =
    iterator(i.iterator)
}
