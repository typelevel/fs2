package fs2.concurrent.pubsub
import fs2.Chunk
import scala.collection.immutable.{Queue => ScalaQueue}

object BoundedQueue {

  /** unbounded fifo strategy **/
  def fifo[A](maxSize: Int): PubSubStrategy[A, Chunk[A], ScalaQueue[A], Int] =
    strategy(maxSize)(_ :+ _)

  /** unbounded lifo strategy **/
  def lifo[A](maxSize: Int): PubSubStrategy[A, Chunk[A], ScalaQueue[A], Int] =
    strategy(maxSize)((q, a) => a +: q)

  /**
    * Creates bounded queue strategy for `A` with configurable append function
    *
    * @param maxSize    Maximum size of `A` before this is full
    * @param append     Function used to append new elements to the queue.
    */
  def strategy[A](maxSize: Int)(append: (ScalaQueue[A], A) => ScalaQueue[A])
    : PubSubStrategy[A, Chunk[A], ScalaQueue[A], Int] =
    new PubSubStrategy[A, Chunk[A], ScalaQueue[A], Int] {
      val unboundedStrategy = UnboundedQueue.mk(append)

      val initial: ScalaQueue[A] = ScalaQueue.empty

      def publish(a: A, queueState: ScalaQueue[A]): ScalaQueue[A] =
        append(queueState, a)

      def accepts(i: A, queueState: ScalaQueue[A]): Boolean =
        queueState.size < maxSize

      def empty(queueState: ScalaQueue[A]): Boolean =
        queueState.isEmpty

      def get(selector: Int, queueState: ScalaQueue[A]): (ScalaQueue[A], Option[Chunk[A]]) =
        unboundedStrategy.get(selector, queueState)

      def subscribe(selector: Int, queueState: ScalaQueue[A]): (ScalaQueue[A], Boolean) =
        (queueState, false)

      def unsubscribe(selector: Int, queueState: ScalaQueue[A]): ScalaQueue[A] =
        queueState
    }

  /**
    * Queue Strategy that allows only single element to be enqueued.
    * Before the `A` is enqueued, at least one subscriber must be ready.
    */
  def synchronous[A]: PubSubStrategy[A, Chunk[A], (Boolean, Option[A]), Int] =
    new PubSubStrategy[A, Chunk[A], (Boolean, Option[A]), Int] {
      def initial: (Boolean, Option[A]) = (false, None)

      def accepts(i: A, queueState: (Boolean, Option[A])): Boolean =
        queueState._1 && queueState._2.isEmpty

      def publish(i: A, queueState: (Boolean, Option[A])): (Boolean, Option[A]) =
        (queueState._1, Some(i))

      def get(selector: Int,
              queueState: (Boolean, Option[A])): ((Boolean, Option[A]), Option[Chunk[A]]) =
        queueState._2 match {
          case None    => ((true, None), None)
          case Some(a) => ((false, None), Some(Chunk.singleton(a)))
        }

      def empty(queueState: (Boolean, Option[A])): Boolean =
        queueState._2.isEmpty

      def subscribe(selector: Int,
                    queueState: (Boolean, Option[A])): ((Boolean, Option[A]), Boolean) =
        (queueState, false)

      def unsubscribe(selector: Int, queueState: (Boolean, Option[A])): (Boolean, Option[A]) =
        queueState
    }

}
