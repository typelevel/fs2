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
import scala.reflect.ClassTag

private[fs2] trait ChunkPlatform[+O] { self: Chunk[O] =>

  def toArraySeq[O2 >: O: ClassTag]: ArraySeq[O2] = {
    val array: Array[O2] = new Array[O2](size)
    copyToArray(array)
    ArraySeq.unsafeWrapArray[O2](array)
  }

  def toArraySeqUntagged: ArraySeq[O] =
    self match {
      case knownType: Chunk.KnownElementType[o] =>
        knownType.toArraySeq[o](knownType.elementClassTag).asInstanceOf[ArraySeq[O]] // Not safe
      case _ =>
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

private[fs2] trait ChunkCompanionPlatform { self: Chunk.type =>

  protected def platformIterable[O](i: Iterable[O]): Option[Chunk[O]] =
    i match {
      case a: immutable.ArraySeq[O] => Some(arraySeq(a))
      case _                        => None
    }

  /**
    * Creates a chunk backed by an immutable `ArraySeq`.
    */
  def arraySeq[O](arraySeq: immutable.ArraySeq[O]): Chunk[O] =
    array(arraySeq.unsafeArray.asInstanceOf[Array[O]])

}
