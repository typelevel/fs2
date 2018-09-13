package fs2.concurrent.pubsub

import cats.Applicative
import cats.syntax.all._
import cats.effect.{Concurrent, ExitCase, Sync}
import cats.effect.concurrent.{Deferred, Ref}
import fs2.internal.Token

import scala.annotation.tailrec
import scala.collection.immutable.{Queue => ScalaQueue}

trait Publish[F[_], A] {

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

trait Subscribe[F[_], A, QS, Selector] {

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

trait PubSub[F[_], I, O, QS, Selector] extends Publish[F, I] with Subscribe[F, O, QS, Selector]

object PubSub {

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
      strategy: PubSubStrategy[I, O, QS, Selector]): F[PubSub[F, I, O, QS, Selector]] = {

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
                if (!strategy.empty(queue)) go(queue, remains.tail, remains, Some(action))
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
    def loop1(ps: PS, action: F[Unit]): (PS, F[Unit]) =
      publishPublishers(ps) match {
        case (ps, resultPublish) =>
          consumeSubscribers(ps) match {
            case (ps, resultConsume) =>
              if (resultConsume.isEmpty && resultPublish.isEmpty) (ps, action)
              else {
                def nextAction =
                  resultConsume.map(action >> _).getOrElse(action) >>
                    resultPublish.getOrElse(Applicative[F].unit)
                loop1(ps, nextAction)
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
          val (ps2, action) = loop1(ps1, Applicative[F].unit)
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

      new PubSub[F, I, O, QS, Selector] {
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
          state.modify { ps =>
            val (queue, success) = strategy.subscribe(selector, ps.queue)
            (ps.copy(queue = queue), success)
          }

        def unsubscribe(selector: Selector): F[Unit] =
          state.update { ps =>
            ps.copy(queue = strategy.unsubscribe(selector, ps.queue))
          }
      }

    }
  }

}
