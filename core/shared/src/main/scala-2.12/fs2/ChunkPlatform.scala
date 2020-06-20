package fs2

import scala.collection.mutable.WrappedArray

trait ChunkPlatform[+O] { self: Chunk[O] => }

trait ChunkCompanionPlatform { self: Chunk.type =>

  protected def platformIterable[O](i: Iterable[O]): Option[Chunk[O]] =
    i match {
      case a: WrappedArray[O] => Some(wrappedArray(a))
      case _                  => None
    }

  /**
    * Creates a chunk backed by a `WrappedArray`
    */
  def wrappedArray[O](wrappedArray: WrappedArray[O]): Chunk[O] =
    array(wrappedArray.array.asInstanceOf[Array[O]])

}
