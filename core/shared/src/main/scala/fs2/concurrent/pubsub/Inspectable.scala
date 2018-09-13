package fs2.concurrent.pubsub

import fs2.internal.Token

object Inspectable {

  /** s
    * State representation for inspectable strategy that
    * keeps track of strategy state and keeps track of all subscribers that have seen the last known state.
    *
    * @param qs           State of the strategy to be inspected
    * @param inspected    List of subscribers that have seen the `qs` already
    */
  case class State[S](
      qs: S,
      inspected: Set[Token]
  )

  def strategy[I, O, S, Sel](
      strategy: PubSubStrategy[I, O, S, Sel]
  ): PubSubStrategy[I, Either[S, O], State[S], Either[Option[Token], Sel]] =
    new PubSubStrategy[I, Either[S, O], State[S], Either[Option[Token], Sel]] {

      def initial: State[S] =
        State(strategy.initial, Set.empty)

      def accepts(i: I, queueState: State[S]): Boolean =
        strategy.accepts(i, queueState.qs)

      def publish(i: I, queueState: State[S]): State[S] =
        State(strategy.publish(i, queueState.qs), Set.empty)

      def get(selector: Either[Option[Token], Sel],
              queueState: State[S]): (State[S], Option[Either[S, O]]) =
        selector match {
          case Left(None) =>
            (queueState, Some(Left(queueState.qs)))
          case Left(Some(token)) =>
            if (queueState.inspected.contains(token)) (queueState, None)
            else
              (queueState.copy(inspected = queueState.inspected + token), Some(Left(queueState.qs)))
          case Right(sel) =>
            val (s, r) = strategy.get(sel, queueState.qs)
            (State(s, Set.empty), r.map(Right(_)))
        }

      def empty(queueState: State[S]): Boolean =
        strategy.empty(queueState.qs)

      def subscribe(selector: Either[Option[Token], Sel],
                    queueState: State[S]): (State[S], Boolean) =
        selector match {
          case Left(None)    => (queueState, false)
          case Left(Some(_)) => (queueState, true)
          case Right(sel) =>
            val (s, result) = strategy.subscribe(sel, queueState.qs)
            (queueState.copy(qs = s), result)
        }
      def unsubscribe(selector: Either[Option[Token], Sel], queueState: State[S]): State[S] =
        selector match {
          case Left(None)        => queueState
          case Left(Some(token)) => queueState.copy(inspected = queueState.inspected - token)
          case Right(sel)        => queueState.copy(qs = strategy.unsubscribe(sel, queueState.qs))
        }
    }

}
