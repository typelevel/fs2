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
  override def equals(other: Any): Boolean =
    other match {
      case that: SizedQueue[_] => size == that.size && underlying == that.underlying
      case _                   => false
    }
  override def hashCode: Int = underlying.hashCode
  override def toString: String = underlying.mkString("SizedQueue(", ", ", ")")
}

private[fs2] object SizedQueue {
  def empty[A]: SizedQueue[A] = new SizedQueue(Queue.empty, 0)
  def one[A](a: A): SizedQueue[A] = new SizedQueue(Queue.empty :+ a, 1)
}
