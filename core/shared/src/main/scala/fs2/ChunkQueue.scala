package fs2

import scala.collection.immutable.{Queue => SQueue}

/**
  * A FIFO queue of chunks that provides an O(1) size method and provides the ability to
  * take and drop individual elements while preserving the chunk structure as much as possible.
  *
  * This is similar to a queue of individual elements but chunk structure is maintained.
  */
final class ChunkQueue[A] private (val chunks: SQueue[Chunk[A]], val size: Int) {
  def iterator: Iterator[A] = chunks.iterator.flatMap(_.iterator)

  /** Prepends a chunk to the start of this chunk queue. */
  def +:(c: Chunk[A]): ChunkQueue[A] = new ChunkQueue(c +: chunks, c.size + size)

  /** Appends a chunk to the end of this chunk queue. */
  def :+(c: Chunk[A]): ChunkQueue[A] = new ChunkQueue(chunks :+ c, size + c.size)

  /** Takes the first `n` elements of this chunk queue in a way that preserves chunk structure. */
  def take(n: Int): ChunkQueue[A] =
    if (n <= 0) ChunkQueue.empty
    else if (n >= size) this
    else {
      def loop(acc: SQueue[Chunk[A]], rem: SQueue[Chunk[A]], toTake: Int): ChunkQueue[A] =
        if (toTake <= 0) new ChunkQueue(acc, n)
        else {
          val next = rem.head
          val nextSize = next.size
          if (nextSize < toTake) loop(acc :+ next, rem.tail, toTake - nextSize)
          else if (nextSize == toTake) new ChunkQueue(acc :+ next, n)
          else new ChunkQueue(acc :+ next.take(toTake), n)
        }
      loop(SQueue.empty, chunks, n)
    }

  /** Takes the right-most `n` elements of this chunk queue in a way that preserves chunk structure. */
  def takeRight(n: Int): ChunkQueue[A] = if (n <= 0) ChunkQueue.empty else drop(size - n)

  /** Drops the first `n` elements of this chunk queue in a way that preserves chunk structure. */
  def drop(n: Int): ChunkQueue[A] =
    if (n <= 0) this
    else if (n >= size) ChunkQueue.empty
    else {
      def loop(rem: SQueue[Chunk[A]], toDrop: Int): ChunkQueue[A] =
        if (toDrop <= 0) new ChunkQueue(rem, size - n)
        else {
          val next = rem.head
          val nextSize = next.size
          if (nextSize < toDrop) loop(rem.tail, toDrop - nextSize)
          else if (nextSize == toDrop) new ChunkQueue(rem.tail, size - n)
          else new ChunkQueue(next.drop(toDrop) +: rem.tail, size - n)
        }
      loop(chunks, n)
    }

  /** Drops the right-most `n` elements of this chunk queue in a way that preserves chunk structure. */
  def dropRight(n: Int): ChunkQueue[A] = if (n <= 0) this else take(size - n)

  /** Converts this chunk queue to a single chunk, copying all chunks to a single chunk. */
  def toChunk: Chunk[A] = Chunk.concat(chunks)

  override def equals(that: Any): Boolean = that match {
    case that: ChunkQueue[A] => size == that.size && chunks == that.chunks
    case _                   => false
  }

  override def hashCode: Int = chunks.hashCode

  override def toString: String = chunks.mkString("ChunkQueue(", ", ", ")")
}

object ChunkQueue {
  private val empty_ = new ChunkQueue(collection.immutable.Queue.empty, 0)
  def empty[A]: ChunkQueue[A] = empty_.asInstanceOf[ChunkQueue[A]]
  def apply[A](chunks: Chunk[A]*): ChunkQueue[A] = chunks.foldLeft(empty[A])(_ :+ _)
}
