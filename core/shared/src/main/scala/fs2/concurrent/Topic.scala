/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2
package concurrent

import cats.Eq
import cats.effect.kernel.Concurrent
import cats.syntax.all._

import fs2.internal.{SizedQueue, Unique}

/** Asynchronous Topic.
  *
  * Topic allows you to distribute `A` published by arbitrary number of publishers to arbitrary number of subscribers.
  *
  * Topic has built-in back-pressure support implemented as maximum bound (`maxQueued`) that a subscriber is allowed to enqueue.
  * Once that bound is hit, publishing may semantically block until the lagging subscriber consumes some of its queued elements.
  *
  * Additionally the subscriber has possibility to terminate whenever size of enqueued elements is over certain size
  * by using `subscribeSize`.
  */
abstract class Topic[F[_], A] { self =>

  /** Publishes elements from source of `A` to this topic and emits a unit for each element published.
    * [[Pipe]] equivalent of `publish1`.
    */
  def publish: Pipe[F, A, Unit]

  /** Publishes one `A` to topic.
    *
    * This waits until `a` is published to all subscribers.
    * If any of the subscribers is over the `maxQueued` limit, this will wait to complete until that subscriber processes
    * enough of its elements such that `a` is enqueued.
    */
  def publish1(a: A): F[Unit]

  /** Subscribes for `A` values that are published to this topic.
    *
    * Pulling on the returned stream opens a "subscription", which allows up to
    * `maxQueued` elements to be enqueued as a result of publication.
    *
    * The first element in the stream is always the last published `A` at the time
    * the stream is first pulled from, followed by each published `A` value from that
    * point forward.
    *
    * If at any point, the queue backing the subscription has `maxQueued` elements in it,
    * any further publications semantically block until elements are dequeued from the
    * subscription queue.
    *
    * @param maxQueued maximum number of elements to enqueue to the subscription
    * queue before blocking publishers
    */
  def subscribe(maxQueued: Int): Stream[F, A]

  /** Like [[subscribe]] but emits an approximate number of queued elements for this subscription
    * with each emitted `A` value.
    */
  def subscribeSize(maxQueued: Int): Stream[F, (A, Int)]

  /** Signal of current active subscribers.
    */
  def subscribers: Stream[F, Int]

  /** Returns an alternate view of this `Topic` where its elements are of type `B`,
    * given two functions, `A => B` and `B => A`.
    */
  def imap[B](f: A => B)(g: B => A): Topic[F, B] =
    new Topic[F, B] {
      def publish: Pipe[F, B, Unit] = sfb => self.publish(sfb.map(g))
      def publish1(b: B): F[Unit] = self.publish1(g(b))
      def subscribe(maxQueued: Int): Stream[F, B] =
        self.subscribe(maxQueued).map(f)
      def subscribers: Stream[F, Int] = self.subscribers
      def subscribeSize(maxQueued: Int): Stream[F, (B, Int)] =
        self.subscribeSize(maxQueued).map { case (a, i) => f(a) -> i }
    }
}

object Topic {

  /** Constructs a `Topic` for a provided `Concurrent` datatype. The
    * `initial` value is immediately published.
    */
  def apply[F[_], A](initial: A)(implicit F: Concurrent[F]): F[Topic[F, A]] = {
    implicit def eqInstance: Eq[Strategy.State[A]] =
      Eq.instance[Strategy.State[A]](_.subscribers.keySet == _.subscribers.keySet)

    PubSub(PubSub.Strategy.Inspectable.strategy(Strategy.boundedSubscribers(initial)))
      .map { pubSub =>
        new Topic[F, A] {
          def subscriber(size: Int): Stream[F, ((Unique, Int), Stream[F, SizedQueue[A]])] =
            Stream
              .bracket(
                Unique[F]
                  .tupleRight(size)
                  .flatTap(selector => pubSub.subscribe(Right(selector)))
              )(selector => pubSub.unsubscribe(Right(selector)))
              .map { selector =>
                selector ->
                  pubSub.getStream(Right(selector)).flatMap {
                    case Right(q) => Stream.emit(q)
                    case Left(_)  => Stream.empty // impossible
                  }
              }

          def publish: Pipe[F, A, Unit] =
            _.evalMap(publish1)

          def publish1(a: A): F[Unit] =
            pubSub.publish(a)

          def subscribe(maxQueued: Int): Stream[F, A] =
            subscriber(maxQueued).flatMap { case (_, s) => s.flatMap(q => Stream.emits(q.toQueue)) }

          def subscribeSize(maxQueued: Int): Stream[F, (A, Int)] =
            subscriber(maxQueued).flatMap { case (selector, stream) =>
              stream
                .flatMap { q =>
                  Stream.emits(q.toQueue.zipWithIndex.map { case (a, idx) => (a, q.size - idx) })
                }
                .evalMap { case (a, remQ) =>
                  pubSub.get(Left(None)).map {
                    case Left(s) =>
                      (a, s.subscribers.get(selector).map(_.size + remQ).getOrElse(remQ))
                    case Right(_) => (a, -1) // impossible
                  }
                }
            }

          def subscribers: Stream[F, Int] =
            Stream
              .bracket(Unique[F])(token => pubSub.unsubscribe(Left(Some(token))))
              .flatMap { token =>
                pubSub.getStream(Left(Some(token))).flatMap {
                  case Left(s)  => Stream.emit(s.subscribers.size)
                  case Right(_) => Stream.empty //impossible
                }
              }
        }
      }
  }

  private[fs2] object Strategy {
    final case class State[A](
        last: A,
        subscribers: Map[(Unique, Int), SizedQueue[A]]
    )

    /** Strategy for topic, where every subscriber can specify max size of queued elements.
      * If that subscription is exceeded any other `publish` to the topic will hold,
      * until such subscriber disappears, or consumes more elements.
      *
      * @param initial Initial value of the topic.
      */
    def boundedSubscribers[F[_], A](
        start: A
    ): PubSub.Strategy[A, SizedQueue[A], State[A], (Unique, Int)] =
      new PubSub.Strategy[A, SizedQueue[A], State[A], (Unique, Int)] {
        def initial: State[A] = State(start, Map.empty)

        def accepts(i: A, state: State[A]): Boolean =
          state.subscribers.forall { case ((_, max), q) => q.size < max }

        def publish(i: A, state: State[A]): State[A] =
          State(
            last = i,
            subscribers = state.subscribers.map { case (k, v) => (k, v :+ i) }
          )

        // Register empty queue
        def regEmpty(selector: (Unique, Int), state: State[A]): State[A] =
          state.copy(subscribers = state.subscribers + (selector -> SizedQueue.empty))

        def get(selector: (Unique, Int), state: State[A]): (State[A], Option[SizedQueue[A]]) =
          state.subscribers.get(selector) match {
            case None =>
              (state, Some(SizedQueue.empty)) // Prevent register, return empty
            case r @ Some(q) =>
              if (q.isEmpty) (state, None)
              else (regEmpty(selector, state), r)
          }

        def empty(state: State[A]): Boolean =
          false

        def subscribe(selector: (Unique, Int), state: State[A]): (State[A], Boolean) =
          (
            state.copy(subscribers = state.subscribers + (selector -> SizedQueue.one(state.last))),
            true
          )

        def unsubscribe(selector: (Unique, Int), state: State[A]): State[A] =
          state.copy(subscribers = state.subscribers - selector)
      }
  }
}
