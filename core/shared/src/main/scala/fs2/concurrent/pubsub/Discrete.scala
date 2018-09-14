package fs2.concurrent.pubsub

import fs2.internal.Token

import scala.annotation.tailrec

object Discrete {

  /**
    * State of the discrete strategy
    *
    * Allows to consume values `A` that may arrive out of order, however signals their discret value in order, as they
    * have been received..
    *
    *
    * @param last         Last value known
    * @param lastStamp    Stamp of the last value. Next value will be accepted, if the incoming `A` will have previous
    *                     stamp set to this value. In that case, the stamp is swapped to new stamp of incoming value and `last` is updated.
    * @param outOfOrder   If there are arriving any `A` which has out of order stamp, this keeps these fro resolving them laters
    * @param seen         A set of subscribers, that have seen the `last`
    * @tparam A
    */
  case class State[A](
      last: A,
      lastStamp: Long,
      outOfOrder: Map[Long, (Long, A)],
      seen: Set[Token]
  )

  /**
    * Strategy providing possibility for a discrete `Get` in correct order.
    *
    * Elements incoming as tuples of their timestamp and value. Also, each publish contains
    * timestamp of previous value, that should be replaced by new value.
    *
    * @param start    initial value `A`
    * @param stamp    initial stamp of `A`
    */
  def strategy[A](
      stamp: Long,
      start: A
  ): PubSubStrategy[(Long, (Long, A)), A, State[A], Option[Token]] =
    new PubSubStrategy[(Long, (Long, A)), A, State[A], Option[Token]] {
      def initial: State[A] =
        State(start, stamp, Map.empty, Set.empty)

      def accepts(i: (Long, (Long, A)), queueState: State[A]): Boolean =
        true

      def publish(i: (Long, (Long, A)), queueState: State[A]): State[A] = {
        val (prev, (stamp, a)) = i
        if (prev != queueState.lastStamp) queueState.copy(outOfOrder = queueState.outOfOrder + i)
        else {
          @tailrec
          def go(stamp: Long, curr: A, outOfOrder: Map[Long, (Long, A)]): State[A] =
            outOfOrder.get(stamp) match {
              case None          => State(curr, stamp, outOfOrder, Set.empty)
              case Some((sa, a)) => go(sa, a, outOfOrder - stamp)
            }
          go(stamp, a, queueState.outOfOrder)
        }
      }

      def get(selector: Option[Token], queueState: State[A]): (State[A], Option[A]) =
        selector match {
          case None => (queueState, Some(queueState.last))
          case Some(token) =>
            if (queueState.seen.contains(token)) (queueState, None)
            else (queueState.copy(seen = queueState.seen + token), Some(queueState.last))
        }

      def empty(queueState: State[A]): Boolean =
        false

      def subscribe(selector: Option[Token], queueState: State[A]): (State[A], Boolean) =
        selector match {
          case None    => (queueState, false)
          case Some(_) => (queueState, true)
        }

      def unsubscribe(selector: Option[Token], queueState: State[A]): State[A] =
        selector match {
          case None        => queueState
          case Some(token) => queueState.copy(seen = queueState.seen - token)
        }
    }

}
