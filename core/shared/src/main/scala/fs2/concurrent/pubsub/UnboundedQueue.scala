package fs2.concurrent.pubsub
import fs2.Chunk

import scala.collection.immutable.{Queue => ScalaQueue}

object UnboundedQueue {

  /** unbounded strategy of CyclicBuffer, that memoize always `maxSize` elements and never blocks. **/
  def circularBuffer[A](maxSize: Int): PubSubStrategy[A, Chunk[A], ScalaQueue[A], Int] =
    strategy { (q, a) =>
      if (q.size < maxSize) q :+ a
      else q.tail :+ a
    }

  /** unbounded fifo strategy **/
  def fifo[A]: PubSubStrategy[A, Chunk[A], ScalaQueue[A], Int] = strategy(_ :+ _)

  /** unbounded lifo strategy **/
  def lifo[A]: PubSubStrategy[A, Chunk[A], ScalaQueue[A], Int] = strategy((q, a) => a +: q)

  /**
    * Creates unbounded queue strategy for `A` with configurable append function
    *
    * @param append Function used to append new elements to the queue.
    */
  def strategy[A](append: (ScalaQueue[A], A) => ScalaQueue[A])
    : PubSubStrategy[A, Chunk[A], ScalaQueue[A], Int] =
    new PubSubStrategy[A, Chunk[A], ScalaQueue[A], Int] {

      val initial: ScalaQueue[A] = ScalaQueue.empty

      def publish(a: A, queueState: ScalaQueue[A]): ScalaQueue[A] =
        append(queueState, a)

      def accepts(i: A, queueState: ScalaQueue[A]): Boolean =
        true

      def empty(queueState: ScalaQueue[A]): Boolean =
        queueState.isEmpty

      def get(selector: Int, queueState: ScalaQueue[A]): (ScalaQueue[A], Option[Chunk[A]]) =
        if (queueState.isEmpty) (queueState, None)
        else {
          val (out, rem) = queueState.splitAt(selector)
          (rem, Some(Chunk.seq(out)))
        }

      def subscribe(selector: Int, queueState: ScalaQueue[A]): (ScalaQueue[A], Boolean) =
        (queueState, false)

      def unsubscribe(selector: Int, queueState: ScalaQueue[A]): ScalaQueue[A] =
        queueState
    }

}
