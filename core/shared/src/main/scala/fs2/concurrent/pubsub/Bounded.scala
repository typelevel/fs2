package fs2.concurrent.pubsub
import fs2.Chunk
import scala.collection.immutable.{Queue => ScalaQueue}

object Bounded {

  /** unbounded fifo strategy **/
  def fifo[A](maxSize: Int): PubSubStrategy[A, Chunk[A], ScalaQueue[A], Int] =
    strategy(maxSize)(UnboundedQueue.fifo[A])(_.size)

  /** unbounded lifo strategy **/
  def lifo[A](maxSize: Int): PubSubStrategy[A, Chunk[A], ScalaQueue[A], Int] =
    strategy(maxSize)(UnboundedQueue.lifo[A])(_.size)

  /**
    * Creates bounded strategy, that won't accepts elements if size produced by `f` is >= `maxSize`
    *
    * @param maxSize    Maximum size of enqueued `A` before this is full
    * @param szOf       Function to extract current size of `S`
    */
  def strategy[A, S](maxSize: Int)(strategy: PubSubStrategy[A, Chunk[A], S, Int])(
      f: S => Int): PubSubStrategy[A, Chunk[A], S, Int] =
    new PubSubStrategy[A, Chunk[A], S, Int] {

      val initial: S = strategy.initial

      def publish(a: A, queueState: S): S =
        strategy.publish(a, queueState)

      def accepts(i: A, queueState: S): Boolean =
        f(queueState) < maxSize

      def empty(queueState: S): Boolean =
        strategy.empty(queueState)

      def get(selector: Int, queueState: S): (S, Option[Chunk[A]]) =
        strategy.get(selector, queueState)

      def subscribe(selector: Int, queueState: S): (S, Boolean) =
        strategy.subscribe(selector, queueState)

      def unsubscribe(selector: Int, queueState: S): S =
        strategy.unsubscribe(selector, queueState)
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
