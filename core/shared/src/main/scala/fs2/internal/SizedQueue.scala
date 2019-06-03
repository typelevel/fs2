package fs2.internal

import scala.collection.immutable.Queue

/** Immutable queue with an O(1) size method. */
private[fs2] final class SizedQueue[A](private val underlying: Queue[A], val size: Int) {
  def +:(a: A): SizedQueue[A] = new SizedQueue(a +: underlying, size + 1)
  def :+(a: A): SizedQueue[A] = new SizedQueue(underlying :+ a, size + 1)
  def isEmpty: Boolean = size == 0
  def headOption: Option[A] = underlying.headOption
  def tail: SizedQueue[A] = if (isEmpty) this else new SizedQueue(underlying.tail, size - 1)
  def toQueue: Queue[A] = underlying
  override def equals(other: Any): Boolean = other match {
    case that: SizedQueue[A] => underlying == that.underlying && size == that.size
    case _                   => false
  }
  override def hashCode: Int = underlying.hashCode
  override def toString: String = underlying.mkString("SizedQueue(", ", ", ")")
}

private[fs2] object SizedQueue {
  def empty[A]: SizedQueue[A] = new SizedQueue(Queue.empty, 0)
  def one[A](a: A): SizedQueue[A] = new SizedQueue(Queue.empty :+ a, 1)
}
