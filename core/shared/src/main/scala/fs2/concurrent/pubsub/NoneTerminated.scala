package fs2.concurrent.pubsub

object NoneTerminated {

  /**
    * Strategy, that allows to form queues that can be closed.
    * Closeable queues are closed by publishing `None` instead of Some(a).
    *
    * The close takes precedence over any elements in the queue.
    *
    * When the queue is closed, then
    *  - every `publish` is successful and completes immediately.
    *  - every `get` completes immediately with None instead of `O`.
    *
    */
  def closeNow[I, O, S, Sel](strategy: PubSubStrategy[I, O, S, Sel])
    : PubSubStrategy[Option[I], Option[O], Option[S], Sel] =
    new PubSubStrategy[Option[I], Option[O], Option[S], Sel] {
      def initial: Option[S] =
        Some(strategy.initial)

      def accepts(i: Option[I], queueState: Option[S]): Boolean =
        i.forall { el =>
          queueState.exists { s =>
            strategy.accepts(el, s)
          }
        }

      def publish(i: Option[I], queueState: Option[S]): Option[S] =
        i match {
          case None     => None
          case Some(el) => queueState.map(s => strategy.publish(el, s))
        }

      def get(selector: Sel, queueState: Option[S]): (Option[S], Option[Option[O]]) =
        queueState match {
          case Some(s) =>
            val (s1, result) = strategy.get(selector, s)
            (Some(s1), result.map(Some(_)))
          case None => (None, Some(None))
        }

      def empty(queueState: Option[S]): Boolean =
        queueState.forall(strategy.empty)

      def subscribe(selector: Sel, queueState: Option[S]): (Option[S], Boolean) =
        queueState match {
          case None => (None, false)
          case Some(s) =>
            val (s1, success) = strategy.subscribe(selector, s)
            (Some(s1), success)
        }
      def unsubscribe(selector: Sel, queueState: Option[S]): Option[S] =
        queueState.map { s =>
          strategy.unsubscribe(selector, s)
        }
    }

  /**
    * Like `closeNow` but instead terminating immediately, it will terminate when all elements in the queue were consumed.
    * When the queue is closed, but not all elements yet consumed, any publish operation will complete immediately.
    *
    */
  def drainAndClose[I, O, S, Sel](strategy: PubSubStrategy[I, O, S, Sel])
    : PubSubStrategy[Option[I], Option[O], (Boolean, S), Sel] =
    new PubSubStrategy[Option[I], Option[O], (Boolean, S), Sel] {
      def initial: (Boolean, S) = (true, strategy.initial)

      def accepts(i: Option[I], queueState: (Boolean, S)): Boolean =
        i match {
          case None     => true
          case Some(el) => !queueState._1 || strategy.accepts(el, queueState._2)
        }

      def publish(i: Option[I], queueState: (Boolean, S)): (Boolean, S) =
        i match {
          case None => (false, queueState._2)
          case Some(el) =>
            if (queueState._1) (true, strategy.publish(el, queueState._2))
            else queueState
        }

      def get(selector: Sel, queueState: (Boolean, S)): ((Boolean, S), Option[Option[O]]) =
        if (queueState._1) {
          val (s1, result) = strategy.get(selector, queueState._2)
          ((true, s1), result.map(Some(_)))
        } else {
          if (strategy.empty(queueState._2)) (queueState, Some(None))
          else {
            val (s1, result) = strategy.get(selector, queueState._2)
            ((false, s1), result.map(Some(_)))
          }
        }

      def empty(queueState: (Boolean, S)): Boolean =
        strategy.empty(queueState._2) && queueState._1

      def subscribe(selector: Sel, queueState: (Boolean, S)): ((Boolean, S), Boolean) =
        if (queueState._1) {
          val (s, success) = strategy.subscribe(selector, queueState._2)
          ((true, s), success)
        } else {
          (queueState, false)
        }

      def unsubscribe(selector: Sel, queueState: (Boolean, S)): (Boolean, S) =
        (queueState._1, strategy.unsubscribe(selector, queueState._2))

    }

}
