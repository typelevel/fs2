package fs2.concurrent

import cats.{Applicative, Eq}
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, ExitCase, Sync}
import cats.syntax.all._
import fs2._
import fs2.internal.Token

import scala.annotation.tailrec
import scala.collection.immutable.{Queue => ScalaQueue}

private[fs2] trait Publish[F[_], A] {

  /**
    * Publishes one element.
    * This completes after element was successfully published.
    */
  def publish(a: A): F[Unit]

  /**
    * Tries to publish one element.
    *
    * Evaluates to `false` if element was not published.
    * Evaluates to `true` if element was published successfully.
    */
  def tryPublish(a: A): F[Boolean]
}

private[fs2] trait Subscribe[F[_], A, Selector] {

  /**
    * Gets elements satisfying the `selector`, yielding when such an element is available.
    *
    * @param selector selector describing which `A` to receive
    */
  def get(selector: Selector): F[A]

  /**
    * A variant of `get`, that instead or returning one element will return multiple elements
    * in form of stream.
    *
    * @param selector selector describing which `A` to receive
    * @return
    */
  def getStream(selector: Selector): Stream[F, A]

  /**
    * Like `get`, but instead of semantically blocking for a matching element, returns immediately
    * with `None` if such an element is not available.
    *
    * @param selector selector describing which `A` to receive
    */
  def tryGet(selector: Selector): F[Option[A]]

  /**
    * Creates a subscription for the supplied selector.
    * If the subscription is not supported or not successful, this yields to false.
    * @param selector selector describing which `A` to receive
    */
  def subscribe(selector: Selector): F[Boolean]

  /**
    * Cancels a subscription previously registered with [[subscribe]].
    * Must be invoked if the subscriber will no longer consume elements.
    *
    * @param selector selector to unsubscribe
    */
  def unsubscribe(selector: Selector): F[Unit]
}

private[fs2] trait PubSub[F[_], I, O, Selector] extends Publish[F, I] with Subscribe[F, O, Selector]

private[fs2] object PubSub {

  private final case class Publisher[F[_], A](
      token: Token,
      i: A,
      signal: Deferred[F, Unit]
  ) {
    def complete(implicit F: Concurrent[F]): F[Unit] =
      F.start(signal.complete(())).void
  }

  private final case class Subscriber[F[_], A, Selector](
      token: Token,
      selector: Selector,
      signal: Deferred[F, A]
  ) {
    def complete(a: A)(implicit F: Concurrent[F]): F[Unit] =
      F.start(signal.complete(a)).void
  }

  private final case class PubSubState[F[_], I, O, QS, Selector](
      queue: QS,
      publishers: ScalaQueue[Publisher[F, I]],
      subscribers: ScalaQueue[Subscriber[F, O, Selector]]
  )

  def apply[F[_]: Concurrent, I, O, QS, Selector](
      strategy: PubSub.Strategy[I, O, QS, Selector]): F[PubSub[F, I, O, Selector]] =
    in[F].from(strategy)

  final class InPartiallyApplied[G[_]](val G: Sync[G]) extends AnyVal {
    def from[F[_]: Concurrent, I, O, QS, Selector](
        strategy: PubSub.Strategy[I, O, QS, Selector]): G[PubSub[F, I, O, Selector]] = {

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
      def go(ps: PS, action: F[Unit]): (PS, F[Unit]) =
        publishPublishers(ps) match {
          case (ps, resultPublish) =>
            consumeSubscribers(ps) match {
              case (ps, resultConsume) =>
                if (resultConsume.isEmpty && resultPublish.isEmpty) (ps, action)
                else {
                  def nextAction =
                    resultConsume.map(action >> _).getOrElse(action) >>
                      resultPublish.getOrElse(Applicative[F].unit)
                  go(ps, nextAction)
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

      implicit val SyncG: Sync[G] = G
      Ref.in[G, F, PS](initial).map { state =>
        def update[X](f: PS => (PS, F[X])): F[X] =
          state.modify { ps =>
            val (ps1, result) = f(ps)
            val (ps2, action) = go(ps1, Applicative[F].unit)
            (ps2, action >> result)
          }.flatten

        def clearPublisher(token: Token)(exitCase: ExitCase[Throwable]): F[Unit] = exitCase match {
          case ExitCase.Completed => Applicative[F].unit
          case ExitCase.Error(_) | ExitCase.Canceled =>
            state.update { ps =>
              ps.copy(publishers = ps.publishers.filterNot(_.token == token))
            }
        }

        def clearSubscriber(token: Token): F[Unit] =
          state.update { ps =>
            ps.copy(subscribers = ps.subscribers.filterNot(_.token == token))
          }

        def clearSubscriberOnCancel(token: Token)(exitCase: ExitCase[Throwable]): F[Unit] =
          exitCase match {
            case ExitCase.Completed                    => Applicative[F].unit
            case ExitCase.Error(_) | ExitCase.Canceled => clearSubscriber(token)
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
                  val token = new Token

                  val sub =
                    Subscriber(token, selector, Deferred.unsafe[F, O])

                  def cancellableGet =
                    Sync[F].guaranteeCase(sub.signal.get)(clearSubscriberOnCancel(token))

                  (ps.copy(subscribers = ps.subscribers :+ sub), cancellableGet)
                case (ps, Some(o)) =>
                  (ps, Applicative[F].pure(o))
              }
            }

          def getStream(selector: Selector): Stream[F, O] =
            Stream.bracket(Sync[F].delay(new Token))(clearSubscriber).flatMap { token =>
              def get_ =
                update { ps =>
                  tryGet_(selector, ps) match {
                    case (ps, None) =>
                      val sub =
                        Subscriber(token, selector, Deferred.unsafe[F, O])

                      (ps.copy(subscribers = ps.subscribers :+ sub), sub.signal.get)

                    case (ps, Some(o)) =>
                      (ps, Applicative[F].pure(o))
                  }
                }

              Stream.repeatEval(get_)
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
  }

  /**
    * Like [[apply]] but initializes state using another effect constructor
    *
    * This builder uses the
    * [[https://typelevel.org/cats/guidelines.html#partially-applied-type-params Partially-Applied Type]]
    * technique.
    */
  def in[G[_]](implicit G: Sync[G]) = new InPartiallyApplied(G)

  /**
    * Describes a the behavior of a `PubSub`.
    *
    * @tparam I the type of element that may be published
    * @tparam O the type of element that may be subscribed for
    * @tparam S the type of the internal state of the strategy
    * @tparam Selector the type of the output element selector
    */
  trait Strategy[I, O, S, Selector] { self =>

    /** Initial state of this strategy. **/
    def initial: S

    /**
      * Verifies if `I` can be accepted.
      *
      * If this yields to true, then the pubsub can accept this element, and interpreter is free to invoke `publish`.
      * If this yields to false, then interpreter holds the publisher, until there is at least one  `get`
      * (either successsful or not) in which case this is consulted again.
      *
      * @param i `I` to publish
      */
    def accepts(i: I, state: S): Boolean

    /**
      * Publishes `I`. This must always succeed.
      *
      * Interpreter must only invoke this when `accepts` yields to true.
      *
      * @param i `I` to publish
      */
    def publish(i: I, state: S): S

    /**
      * Gets `O`, selected by `selector`.
      *
      * Yields to `None`, if subscriber cannot be satisfied, causing the subscriber to hold, until next successful `publish`
      * Yields to `Some((s,o))` if the subscriber may be satisfied.
      *
      * @param selector specifies which `O` this `get` is interested in. In case of a subscription
      *                 based strategy, the `Selector` shall hold the identity of the subscriber.
      */
    def get(selector: Selector, state: S): (S, Option[O])

    /**
      * Yields to true if there are no elements to `get`.
      */
    def empty(state: S): Boolean

    /**
      * Consulted by interpreter to subscribe the given selector.
      * A subscriptions manages context/state across multiple `get` requests.
      * Yields to false if the subscription cannot be satisfied.
      *
      * @param selector selector that shall be used with mulitple subsequent `get` operations
      */
    def subscribe(selector: Selector, state: S): (S, Boolean)

    /**
      * When strategy supports long-term subscriptions, this is used by interpreter
      * to cancel a previous subscription, indicating the subscriber is no longer interested
      * in getting more data.
      *
      * @param selector selector whose selection shall be canceled
      */
    def unsubscribe(selector: Selector, state: S): S

    /** Transforms selector to selector of this state by applying the `f` to `Sel2` and state of this strategy. **/
    def transformSelector[Sel2](f: (Sel2, S) => Selector): PubSub.Strategy[I, O, S, Sel2] =
      new PubSub.Strategy[I, O, S, Sel2] {
        def initial: S =
          self.initial
        def accepts(i: I, state: S): Boolean =
          self.accepts(i, state)
        def publish(i: I, state: S): S =
          self.publish(i, state)
        def get(selector: Sel2, state: S): (S, Option[O]) =
          self.get(f(selector, state), state)
        def empty(state: S): Boolean =
          self.empty(state)
        def subscribe(selector: Sel2, state: S): (S, Boolean) =
          self.subscribe(f(selector, state), state)
        def unsubscribe(selector: Sel2, state: S): S =
          self.unsubscribe(f(selector, state), state)
      }
  }

  object Strategy {

    /**
      * Creates bounded strategy, that won't accept elements if size produced by `f` is >= `maxSize`.
      *
      * @param maxSize maximum size of enqueued `A` before this is full
      * @param f function to extract current size of `S`
      */
    def bounded[A, S](maxSize: Int)(strategy: PubSub.Strategy[A, Chunk[A], S, Int])(
        f: S => Int): PubSub.Strategy[A, Chunk[A], S, Int] =
      new PubSub.Strategy[A, Chunk[A], S, Int] {

        val initial: S = strategy.initial

        def publish(a: A, state: S): S =
          strategy.publish(a, state)

        def accepts(i: A, state: S): Boolean =
          f(state) < maxSize

        def empty(state: S): Boolean =
          strategy.empty(state)

        def get(selector: Int, state: S): (S, Option[Chunk[A]]) =
          strategy.get(selector, state)

        def subscribe(selector: Int, state: S): (S, Boolean) =
          strategy.subscribe(selector, state)

        def unsubscribe(selector: Int, state: S): S =
          strategy.unsubscribe(selector, state)
      }

    /**
      * Adapts a strategy to one that supports closing.
      *
      * Closeable PubSub are closed by publishing `None` instead of Some(a).
      * The close takes precedence over any elements.
      *
      * When the PubSub is closed, then
      *  - every `publish` is successful and completes immediately.
      *  - every `get` completes immediately with None instead of `O`.
      */
    def closeNow[I, O, S, Sel](strategy: PubSub.Strategy[I, O, S, Sel])
      : PubSub.Strategy[Option[I], Option[O], Option[S], Sel] =
      new PubSub.Strategy[Option[I], Option[O], Option[S], Sel] {
        def initial: Option[S] =
          Some(strategy.initial)

        def accepts(i: Option[I], state: Option[S]): Boolean =
          i.forall { el =>
            state.exists { s =>
              strategy.accepts(el, s)
            }
          }

        def publish(i: Option[I], state: Option[S]): Option[S] =
          i match {
            case None     => None
            case Some(el) => state.map(s => strategy.publish(el, s))
          }

        def get(selector: Sel, state: Option[S]): (Option[S], Option[Option[O]]) =
          state match {
            case Some(s) =>
              val (s1, result) = strategy.get(selector, s)
              (Some(s1), result.map(Some(_)))
            case None => (None, Some(None))
          }

        def empty(state: Option[S]): Boolean =
          state.forall(strategy.empty)

        def subscribe(selector: Sel, state: Option[S]): (Option[S], Boolean) =
          state match {
            case None => (None, false)
            case Some(s) =>
              val (s1, success) = strategy.subscribe(selector, s)
              (Some(s1), success)
          }
        def unsubscribe(selector: Sel, state: Option[S]): Option[S] =
          state.map { s =>
            strategy.unsubscribe(selector, s)
          }
      }

    /**
      * Like [[closeNow]] but instead of terminating immediately,
      * the pubsub will terminate when all elements are consumed.
      *
      * When the PubSub is closed, but not all elements yet consumed,
      * any publish operation will complete immediately.
      */
    def closeDrainFirst[I, O, S, Sel](strategy: PubSub.Strategy[I, O, S, Sel])
      : PubSub.Strategy[Option[I], Option[O], (Boolean, S), Sel] =
      new PubSub.Strategy[Option[I], Option[O], (Boolean, S), Sel] {
        def initial: (Boolean, S) = (true, strategy.initial)

        def accepts(i: Option[I], state: (Boolean, S)): Boolean =
          i match {
            case None     => true
            case Some(el) => !state._1 || strategy.accepts(el, state._2)
          }

        def publish(i: Option[I], state: (Boolean, S)): (Boolean, S) =
          i match {
            case None => (false, state._2)
            case Some(el) =>
              if (state._1) (true, strategy.publish(el, state._2))
              else state
          }

        def get(selector: Sel, state: (Boolean, S)): ((Boolean, S), Option[Option[O]]) =
          if (state._1) {
            val (s1, result) = strategy.get(selector, state._2)
            ((true, s1), result.map(Some(_)))
          } else {
            if (strategy.empty(state._2)) (state, Some(None))
            else {
              val (s1, result) = strategy.get(selector, state._2)
              ((false, s1), result.map(Some(_)))
            }
          }

        def empty(state: (Boolean, S)): Boolean =
          strategy.empty(state._2) && state._1

        def subscribe(selector: Sel, state: (Boolean, S)): ((Boolean, S), Boolean) =
          if (state._1) {
            val (s, success) = strategy.subscribe(selector, state._2)
            ((true, s), success)
          } else {
            (state, false)
          }

        def unsubscribe(selector: Sel, state: (Boolean, S)): (Boolean, S) =
          (state._1, strategy.unsubscribe(selector, state._2))

      }

    object Discrete {

      /**
        * State of the discrete strategy.
        *
        * Allows consumption of `A` values that may arrive out of order,
        * however signals the discrete values in order, as they have been received.
        *
        * @param last         Last value known
        * @param lastStamp    Stamp of the last value. Next value will be accepted, if the incoming `A` will have previous
        *                     stamp set to this value. In that case, the stamp is swapped to new stamp of incoming value and `last` is updated.
        * @param outOfOrder   If there are arriving any `A` which has out of order stamp, this keeps these from resolving them laters
        * @param seen         A set of subscribers, that have seen the `last`
        */
      final case class State[A](
          last: A,
          lastStamp: Long,
          outOfOrder: Map[Long, (Long, A)],
          seen: Set[Token]
      )

      /**
        * Strategy providing possibility for a discrete `get` in correct order.
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

          def accepts(i: (Long, (Long, A)), state: State[A]): Boolean =
            true

          def publish(i: (Long, (Long, A)), state: State[A]): State[A] = {
            val (prev, (stamp, a)) = i
            if (prev != state.lastStamp)
              state.copy(outOfOrder = state.outOfOrder + i)
            else {
              @tailrec
              def go(stamp: Long, curr: A, outOfOrder: Map[Long, (Long, A)]): State[A] =
                outOfOrder.get(stamp) match {
                  case None          => State(curr, stamp, outOfOrder, Set.empty)
                  case Some((sa, a)) => go(sa, a, outOfOrder - stamp)
                }
              go(stamp, a, state.outOfOrder)
            }
          }

          def get(selector: Option[Token], state: State[A]): (State[A], Option[A]) =
            selector match {
              case None => (state, Some(state.last))
              case Some(token) =>
                if (state.seen.contains(token)) (state, None)
                else (state.copy(seen = state.seen + token), Some(state.last))
            }

          def empty(state: State[A]): Boolean =
            false

          def subscribe(selector: Option[Token], state: State[A]): (State[A], Boolean) =
            selector match {
              case None    => (state, false)
              case Some(_) => (state, true)
            }

          def unsubscribe(selector: Option[Token], state: State[A]): State[A] =
            selector match {
              case None        => state
              case Some(token) => state.copy(seen = state.seen - token)
            }
        }

    }

    object Inspectable {

      /**
        * State representation for inspectable strategy that
        * keeps track of strategy state and keeps track of all subscribers that have seen the last known state.
        *
        * @param qs           State of the strategy to be inspected
        * @param inspected    List of subscribers that have seen the `qs` already
        */
      final case class State[S](
          qs: S,
          inspected: Set[Token]
      )

      /**
        * Allows to enhance the supplied strategy by ability to inspect the state.
        * If the `S` is same as previous state (by applying the supplied `Eq` then
        * `get` will not be signalled, if invoked with `Left(Some(token))` - subscription based
        * subscriber.
        *
        * @return
        */
      def strategy[I, O, S: Eq, Sel](
          strategy: PubSub.Strategy[I, O, S, Sel]
      ): PubSub.Strategy[I, Either[S, O], State[S], Either[Option[Token], Sel]] =
        new PubSub.Strategy[I, Either[S, O], State[S], Either[Option[Token], Sel]] {

          def initial: State[S] =
            State(strategy.initial, Set.empty)

          def accepts(i: I, state: State[S]): Boolean =
            strategy.accepts(i, state.qs)

          def publish(i: I, state: State[S]): State[S] = {
            val qs1 = strategy.publish(i, state.qs)
            if (Eq[S].eqv(state.qs, qs1)) state.copy(qs = qs1)
            else State(qs1, Set.empty)
          }

          def get(selector: Either[Option[Token], Sel],
                  state: State[S]): (State[S], Option[Either[S, O]]) =
            selector match {
              case Left(None) =>
                (state, Some(Left(state.qs)))
              case Left(Some(token)) =>
                if (state.inspected.contains(token)) (state, None)
                else
                  (state.copy(inspected = state.inspected + token), Some(Left(state.qs)))
              case Right(sel) =>
                val (s, r) = strategy.get(sel, state.qs)
                val tokens: Set[Token] = if (Eq[S].eqv(state.qs, s)) state.inspected else Set.empty
                (State(s, tokens), r.map(Right(_)))
            }

          def empty(state: State[S]): Boolean =
            strategy.empty(state.qs)

          def subscribe(selector: Either[Option[Token], Sel],
                        state: State[S]): (State[S], Boolean) =
            selector match {
              case Left(None)    => (state, false)
              case Left(Some(_)) => (state, true)
              case Right(sel) =>
                val (s, result) = strategy.subscribe(sel, state.qs)
                (state.copy(qs = s), result)
            }
          def unsubscribe(selector: Either[Option[Token], Sel], state: State[S]): State[S] =
            selector match {
              case Left(None)        => state
              case Left(Some(token)) => state.copy(inspected = state.inspected - token)
              case Right(sel) =>
                val qs1 = strategy.unsubscribe(sel, state.qs)
                val tokens: Set[Token] =
                  if (cats.Eq[S].eqv(state.qs, qs1)) state.inspected else Set.empty
                State(qs1, tokens)
            }
        }
    }
  }
}
