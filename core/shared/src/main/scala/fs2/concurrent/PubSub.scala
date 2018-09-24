package fs2.concurrent

import cats.Applicative
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, ExitCase, Sync}
import cats.syntax.all._
import fs2.Chunk
import fs2.internal.Token

import scala.annotation.tailrec
import scala.collection.immutable.{Queue => ScalaQueue}

private[fs2] trait Publish[F[_], A] {

  /**
    * Publishes one element
    * This completes after element was unsuccessfully published.
    * If chunk is empty, this will complete immediately.
    */
  def publish(a: A): F[Unit]

  /**
    * Tries to publish one element.
    *
    * Evaluates to `false` if element was not published
    * Evaluates to `true` if element was published successfully.
    *
    *
    */
  def tryPublish(a: A): F[Boolean]

}

private[fs2] trait Subscribe[F[_], A, Selector] {

  /**
    * Gets element satisfying the `selector`, Yields whenever element is available.
    *
    * @param selector   Selector describing which `A` to receive
    * @return
    */
  def get(selector: Selector): F[A]

  /**
    * Like `get` only when element is not available, this will yield to None
    * instead awaiting for  element to be available.
    * @param selector   Selector describing which `A` to receive
    * @return
    */
  def tryGet(selector: Selector): F[Option[A]]

  /**
    * Subscribes for strategies supporting subscriptions.
    * If the subscription is not supported or not successful, this yields to false.
    * @param selector  Selector use to select with `A` to receive.
    * @return
    */
  def subscribe(selector: Selector): F[Boolean]

  /**
    * Allows to unsubscribe previous subscription with `subscribe`. Must be invoked if the `subscriber` is known to
    * not anymore consume any elements.
    *
    * @param selector Selector to unsubscribe
    * @return
    */
  def unsubscribe(selector: Selector): F[Unit]

}

private[fs2] trait PubSub[F[_], I, O, Selector] extends Publish[F, I] with Subscribe[F, O, Selector]

private[fs2] object PubSub {

  private case class Publisher[F[_], A](
      token: Token,
      i: A,
      signal: Deferred[F, Unit]
  ) {
    def complete(implicit F: Concurrent[F]): F[Unit] =
      F.start(signal.complete(())).void
  }

  private case class Subscriber[F[_], A, Selector](
      token: Token,
      selector: Selector,
      signal: Deferred[F, A]
  ) {
    def complete(a: A)(implicit F: Concurrent[F]): F[Unit] =
      F.start(signal.complete(a)).void
  }

  private case class PubSubState[F[_], I, O, QS, Selector](
      queue: QS,
      publishers: ScalaQueue[Publisher[F, I]],
      subscribers: ScalaQueue[Subscriber[F, O, Selector]]
  )

  def apply[F[_]: Concurrent, I, O, QS, Selector](
      strategy: PubSub.Strategy[I, O, QS, Selector]): F[PubSub[F, I, O, Selector]] = {

    type PS = PubSubState[F, I, O, QS, Selector]

    def initial: PS = PubSubState(strategy.initial, ScalaQueue.empty, ScalaQueue.empty)

    // runs all subscribers
    // yields to None, if no subscriber was completed
    // yields to Some(nextPS, completeAction) whenever at least one subscriber completes
    // before this finishes this always tries to consume all subscribers in order they have been
    // registered unless strategy signals it is empty.
    def consumeSubscribers(ps: PS): (PS, Option[F[Unit]]) = {
      @tailrec
      def go(queue: QS,
             remains: ScalaQueue[Subscriber[F, O, Selector]],
             keep: ScalaQueue[Subscriber[F, O, Selector]],
             acc: Option[F[Unit]]): (PS, Option[F[Unit]]) =
        remains.headOption match {
          case None =>
            (ps.copy(queue = queue, subscribers = keep), acc)

          case Some(sub) =>
            strategy.get(sub.selector, queue) match {
              case (queue, None) =>
                go(queue, remains.tail, keep :+ sub, acc)
              case (queue, Some(chunk)) =>
                def action = acc.map(_ >> sub.complete(chunk)).getOrElse(sub.complete(chunk))
                if (!strategy.empty(queue)) go(queue, remains.tail, keep, Some(action))
                else (ps.copy(queue = queue, subscribers = keep ++ remains.tail), Some(action))
            }
        }
      go(ps.queue, ps.subscribers, ScalaQueue.empty, None)
    }

    // tries to publish all publishers awaiting
    // yields to None if no single publisher published
    // yields to Some(nextPS, publishSignal) if at least one publisher was publishing to the queue
    // always tries to publish all publishers reminaing in single cycle, even when one publisher succeeded
    def publishPublishers(ps: PS): (PS, Option[F[Unit]]) = {
      @tailrec
      def go(queue: QS,
             remains: ScalaQueue[Publisher[F, I]],
             keep: ScalaQueue[Publisher[F, I]],
             acc: Option[F[Unit]]): (PS, Option[F[Unit]]) =
        remains.headOption match {
          case None =>
            (ps.copy(queue = queue, publishers = keep), acc)

          case Some(pub) =>
            if (strategy.accepts(pub.i, queue)) {
              val queue1 = strategy.publish(pub.i, queue)
              def action = acc.map(_ >> pub.complete).getOrElse(pub.complete)
              go(queue1, remains.tail, keep, Some(action))
            } else {
              go(queue, remains.tail, keep :+ pub, acc)
            }
        }
      go(ps.queue, ps.publishers, ScalaQueue.empty, None)
    }

    /*
     * Central loop. This is consulted always to make sure there are not any not-satisfied publishers // subscribers
     * since last publish/get op
     * this tries to satisfy publishers/subscribers interchangeably until
     * there was no successful publish // subscription in the last loop
     */
    @tailrec
    def loop(ps: PS, action: F[Unit]): (PS, F[Unit]) =
      publishPublishers(ps) match {
        case (ps, resultPublish) =>
          consumeSubscribers(ps) match {
            case (ps, resultConsume) =>
              if (resultConsume.isEmpty && resultPublish.isEmpty) (ps, action)
              else {
                def nextAction =
                  resultConsume.map(action >> _).getOrElse(action) >>
                    resultPublish.getOrElse(Applicative[F].unit)
                loop(ps, nextAction)
              }
          }
      }

    def tryGet_(selector: Selector, ps: PS): (PS, Option[O]) =
      strategy.get(selector, ps.queue) match {
        case (queue, result) =>
          (ps.copy(queue = queue), result)
      }

    def publish_(i: I, ps: PS): PS =
      ps.copy(queue = strategy.publish(i, ps.queue))

    Ref.of[F, PS](initial).map { state =>
      def update[X](f: PS => (PS, F[X])): F[X] =
        state.modify { ps =>
          val (ps1, result) = f(ps)
          val (ps2, action) = loop(ps1, Applicative[F].unit)
          (ps2, action >> result)
        }.flatten

      def clearPublisher(token: Token)(exitCase: ExitCase[Throwable]): F[Unit] = exitCase match {
        case ExitCase.Completed => Applicative[F].unit
        case ExitCase.Error(_) | ExitCase.Canceled =>
          state.update { ps =>
            ps.copy(publishers = ps.publishers.filterNot(_.token == token))
          }
      }

      def clearSubscriber(token: Token)(exitCase: ExitCase[Throwable]): F[Unit] = exitCase match {
        case ExitCase.Completed => Applicative[F].unit
        case ExitCase.Error(_) | ExitCase.Canceled =>
          state.update { ps =>
            ps.copy(subscribers = ps.subscribers.filterNot(_.token == token))
          }

      }

      new PubSub[F, I, O, Selector] {
        def publish(i: I): F[Unit] =
          update { ps =>
            if (strategy.accepts(i, ps.queue)) {

              val ps1 = publish_(i, ps)
              (ps1, Applicative[F].unit)
            } else {
              val publisher = Publisher(new Token, i, Deferred.unsafe[F, Unit])

              def awaitCancellable =
                Sync[F].guaranteeCase(publisher.signal.get)(clearPublisher(publisher.token))

              (ps.copy(publishers = ps.publishers :+ publisher), awaitCancellable)
            }
          }

        def tryPublish(i: I): F[Boolean] =
          update { ps =>
            if (!strategy.accepts(i, ps.queue)) (ps, Applicative[F].pure(false))
            else {
              val ps1 = publish_(i, ps)
              (ps1, Applicative[F].pure(true))
            }
          }

        def get(selector: Selector): F[O] =
          update { ps =>
            tryGet_(selector, ps) match {
              case (ps, None) =>
                val sub =
                  Subscriber(new Token, selector, Deferred.unsafe[F, O])
                def cancellableGet =
                  Sync[F].guaranteeCase(sub.signal.get)(clearSubscriber(sub.token))

                (ps.copy(subscribers = ps.subscribers :+ sub), cancellableGet)
              case (ps, Some(o)) =>
                (ps, Applicative[F].pure(o))
            }
          }

        def tryGet(selector: Selector): F[Option[O]] =
          update { ps =>
            val (ps1, result) = tryGet_(selector, ps)
            (ps1, Applicative[F].pure(result))
          }

        def subscribe(selector: Selector): F[Boolean] =
          update { ps =>
            val (queue, success) = strategy.subscribe(selector, ps.queue)
            (ps.copy(queue = queue), Applicative[F].pure(success))
          }

        def unsubscribe(selector: Selector): F[Unit] =
          update { ps =>
            (ps.copy(queue = strategy.unsubscribe(selector, ps.queue)), Applicative[F].unit)
          }
      }

    }
  }

  trait Strategy[I, O, S, Selector] { self =>

    /** provides initial state **/
    def initial: S

    /**
      * Verifies if `I` can be accepted in the queue.
      * If, this yields to true, then queue can accept this element, and interpreter is free to invoke `publish`.
      * If this yields to false, then interpreter holds the publisher, until there is at least one  `get`
      * (either successsful or not) in which case this is consulted again.
      *
      * @param i            `I` to publish
      * @return
      */
    def accepts(i: I, queueState: S): Boolean

    /**
      * Publishes `I`. This must always succeed.
      * Interpreter guarantees to invoke this only when `accepts` yields to true.
      *
      * @param i An `I` to publish
      */
    def publish(i: I, queueState: S): S

    /**
      * Gets `O`, selected by `selector`.
      *
      * Yields to None, if subscriber cannot be satisfied, causing the subscriber to hold, until next successful `publish`
      * Yields to Some((s,o)) if the subscriber may be satisfied.
      *
      * @param selector     Selector, to select any `O` this `get` is interested in. In case of the subscription
      *                     based strategy, the `Selector` shall hold identity of the subscriber.
      */
    def get(selector: Selector, queueState: S): (S, Option[O])

    /**
      * Yields to true, indicating there are no elements to `get`.
      */
    def empty(queueState: S): Boolean

    /**
      * Consulted by interpreter to subscribe given selector. Subscriptions allows to manage context across multiple `Get` requests.
      * Yields to false, in case the subscription cannot be satisfied.
      *
      * @param selector     Selector, that shall be used with mulitple subsequent `get` operations
      */
    def subscribe(selector: Selector, queueState: S): (S, Boolean)

    /**
      * When strategy supports long-term subscriptions, this is used by interpreter to signal, that such long
      * term subscriber is no longer interested in getting more data.
      *
      * @param selector Selector, whose selection shall be cancelled. Shall contain subscriber's identity.
      */
    def unsubscribe(selector: Selector, queueState: S): S

    /** transforms selector to selector of this state by applying the `f` to Sel2 and state of this strategy **/
    def transformSelector[Sel2](f: (Sel2, S) => Selector): PubSub.Strategy[I, O, S, Sel2] =
      new PubSub.Strategy[I, O, S, Sel2] {
        def initial: S =
          self.initial
        def accepts(i: I, queueState: S): Boolean =
          self.accepts(i, queueState)
        def publish(i: I, queueState: S): S =
          self.publish(i, queueState)
        def get(selector: Sel2, queueState: S): (S, Option[O]) =
          self.get(f(selector, queueState), queueState)
        def empty(queueState: S): Boolean =
          self.empty(queueState)
        def subscribe(selector: Sel2, queueState: S): (S, Boolean) =
          self.subscribe(f(selector, queueState), queueState)
        def unsubscribe(selector: Sel2, queueState: S): S =
          self.unsubscribe(f(selector, queueState), queueState)
      }

  }

  object Strategy {

    /**
      * Creates bounded strategy, that won't accepts elements if size produced by `f` is >= `maxSize`
      *
      * @param maxSize    Maximum size of enqueued `A` before this is full
      * @param f          Function to extract current size of `S`
      */
    def bounded[A, S](maxSize: Int)(strategy: PubSub.Strategy[A, Chunk[A], S, Int])(
        f: S => Int): PubSub.Strategy[A, Chunk[A], S, Int] =
      new PubSub.Strategy[A, Chunk[A], S, Int] {

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
      * Strategy, that allows to form PubSub that can be closed.
      * Closeable PubSub are closed by publishing `None` instead of Some(a).
      *
      * The close takes precedence over any elements in the queue.
      *
      * When the PubSub is closed, then
      *  - every `publish` is successful and completes immediately.
      *  - every `get` completes immediately with None instead of `O`.
      *
      */
    def closeNow[I, O, S, Sel](strategy: PubSub.Strategy[I, O, S, Sel])
      : PubSub.Strategy[Option[I], Option[O], Option[S], Sel] =
      new PubSub.Strategy[Option[I], Option[O], Option[S], Sel] {
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
      * Like `closeNow` but instead terminating immediately, it will terminate when all elements in the PubSub were consumed.
      * When the PubSub is closed, but not all elements yet consumed, any publish operation will complete immediately.
      *
      */
    def closeDrainFirst[I, O, S, Sel](strategy: PubSub.Strategy[I, O, S, Sel])
      : PubSub.Strategy[Option[I], Option[O], (Boolean, S), Sel] =
      new PubSub.Strategy[Option[I], Option[O], (Boolean, S), Sel] {
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
        * @param outOfOrder   If there are arriving any `A` which has out of order stamp, this keeps these from resolving them laters
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
      ): PubSub.Strategy[(Long, (Long, A)), A, State[A], Option[Token]] =
        new PubSub.Strategy[(Long, (Long, A)), A, State[A], Option[Token]] {
          def initial: State[A] =
            State(start, stamp, Map.empty, Set.empty)

          def accepts(i: (Long, (Long, A)), queueState: State[A]): Boolean =
            true

          def publish(i: (Long, (Long, A)), queueState: State[A]): State[A] = {
            val (prev, (stamp, a)) = i
            if (prev != queueState.lastStamp)
              queueState.copy(outOfOrder = queueState.outOfOrder + i)
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
          strategy: PubSub.Strategy[I, O, S, Sel]
      ): PubSub.Strategy[I, Either[S, O], State[S], Either[Option[Token], Sel]] =
        new PubSub.Strategy[I, Either[S, O], State[S], Either[Option[Token], Sel]] {

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
                  (queueState.copy(inspected = queueState.inspected + token),
                   Some(Left(queueState.qs)))
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

  }

}
