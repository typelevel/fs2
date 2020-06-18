package fs2

import scala.collection.immutable.ArraySeq
import scala.collection.{immutable, mutable}
import scala.reflect.ClassTag

trait ChunkPlatform[+O] { self: Chunk[O] =>

  def toArraySeq[O2 >: O : ClassTag]: ArraySeq[O2] = {
    val array: Array[O2] = new Array[O2](size)
    copyToArray(array)
    ArraySeq.unsafeWrapArray[O2](array)
  }

  def toArraySeqUntagged: ArraySeq[O] = {
    self match {
      case knownType: Chunk.KnownElementType[O] => toArraySeq[O](knownType.elementClassTag)
      case _ => {
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
  }

}

trait ChunkCompanionPlatform { self: Chunk.type =>

  // The array casts below are safe because the array constructor will check for primative vs boxed arrays

  def arraySeq[O](arraySeq: immutable.ArraySeq[O]): Chunk[O] =
    array(arraySeq.unsafeArray.asInstanceOf[Array[O]])

  def arraySeq[O](arraySeq: mutable.ArraySeq[O]): Chunk[O] =
    array(arraySeq.array.asInstanceOf[Array[O]])

}
