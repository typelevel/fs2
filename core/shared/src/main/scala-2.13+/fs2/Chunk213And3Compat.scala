package fs2

import scala.collection.immutable.ArraySeq
import scala.collection.immutable
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
}

private[fs2] trait ChunkCompanion213And3Compat { self: Chunk.type =>
  protected def platformIterable[O](i: Iterable[O]): Option[Chunk[O]] =
    i match {
      case a: immutable.ArraySeq[O] => Some(arraySeq(a))
      case _                        => None
    }

  /** Creates a chunk backed by an immutable `ArraySeq`. */
  def arraySeq[O](arraySeq: immutable.ArraySeq[O]): Chunk[O] = {
    val arr = arraySeq.unsafeArray.asInstanceOf[Array[O]]
    array(arr)(ClassTag[O](arr.getClass.getComponentType))
  }

  /** Creates a chunk from a `scala.collection.IterableOnce`. */
  def iterableOnce[O](i: collection.IterableOnce[O]): Chunk[O] =
    iterator(i.iterator)
}
