package fs2.concurrent.pubsub
import fs2.Chunk

object UnboundedQueue {

  /**
    * Creates unbounded queue strategy for `A` with configurable append function
    *
    * @param append Function used to append new elements to the queue.
    */
  def mk[A](append: (Vector[A], A) => Vector[A]): PubSubStrategy[A, Chunk[A], Vector[A], Int] =
    new PubSubStrategy[A, Chunk[A], Vector[A], Int] {

      val initial: Vector[A] = Vector.empty

      def publish(a: A, queueState: Vector[A]): Vector[A] =
        append(queueState, a)

      def accepts(i: A, queueState: Vector[A]): Boolean =
        true

      def empty(queueState: Vector[A]): Boolean =
        queueState.isEmpty

      def get(selector: Int, queueState: Vector[A]): (Vector[A], Option[Chunk[A]]) =
        if (queueState.isEmpty) (queueState, None)
        else {
          val (out, rem) = queueState.splitAt(selector)
          (rem, Some(Chunk.seq(out)))
        }

      def subscribe(selector: Int, queueState: Vector[A]): (Vector[A], Boolean) =
        (queueState, false)

      def unsubscribe(selector: Int, queueState: Vector[A]): Vector[A] =
        queueState
    }

  /** unbounded fifo strategy **/
  def strategy[A]: PubSubStrategy[A, Chunk[A], Vector[A], Int] = fifo

  /** unbounded fifo strategy **/
  def fifo[A]: PubSubStrategy[A, Chunk[A], Vector[A], Int] = mk(_ :+ _)

  /** unbounded lifo strategy **/
  def lifo[A]: PubSubStrategy[A, Chunk[A], Vector[A], Int] = mk((q, a) => a +: q)

  /** unbounded strategy of CyclicBuffer, that memoize always `maxSize` elements and never blocks. **/
  def circularBuffer[A](maxSize: Int): PubSubStrategy[A, Chunk[A], Vector[A], Int] = mk { (q, a) =>
    if (q.size < maxSize) q :+ a
    else q.tail :+ a
  }

}
