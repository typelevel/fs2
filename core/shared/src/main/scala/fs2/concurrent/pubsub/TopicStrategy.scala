package fs2.concurrent.pubsub

import fs2.internal.Token

import scala.collection.immutable.{Queue => ScalaQueue}

object TopicStrategy {

  case class State[A](
      last: A,
      subcribers: Map[(Token, Int), ScalaQueue[A]]
  )

  /**
    * Strategy for topic, where every subscriber can specify max size of queued elements.
    * If that subscription is exceeded any other `publish` to the topic will hold,
    * until such subscriber disappears, or consumes more elements.
    *
    * @param initial  Initial value of the topic.
    */
  def boundedSubscribers[F[_], A](
      start: A): PubSubStrategy[A, ScalaQueue[A], State[A], (Token, Int)] =
    new PubSubStrategy[A, ScalaQueue[A], State[A], (Token, Int)] {
      def initial: State[A] = State(start, Map.empty)
      def accepts(i: A, queueState: State[A]): Boolean =
        queueState.subcribers.forall { case ((_, max), q) => q.size < max }

      def publish(i: A, queueState: State[A]): State[A] =
        State(
          last = i,
          subcribers = queueState.subcribers.mapValues(_ :+ i)
        )

      def get(selector: (Token, Int), queueState: State[A]): (State[A], Option[ScalaQueue[A]]) =
        queueState.subcribers.get(selector) match {
          case None =>
            (queueState, Some(ScalaQueue(queueState.last)))
          case r @ Some(q) =>
            if (q.isEmpty) (queueState, None)
            else {
              (queueState.copy(subcribers = queueState.subcribers + (selector -> ScalaQueue.empty)),
               r)
            }
        }

      def empty(queueState: State[A]): Boolean =
        false

      def subscribe(selector: (Token, Int), queueState: State[A]): (State[A], Boolean) =
        (queueState.copy(
           subcribers = queueState.subcribers + (selector -> ScalaQueue(queueState.last))),
         true)

      def unsubscribe(selector: (Token, Int), queueState: State[A]): State[A] =
        queueState.copy(subcribers = queueState.subcribers - selector)
    }

}
