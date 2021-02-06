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
import cats.effect.Resource
import cats.effect.implicits._
import cats.syntax.all._
import scala.collection.immutable.LongMap

import fs2.internal.{SizedQueue, Unique}

/** Asynchronous Topic.
  *
  * Topic allows you to distribute `A` published by arbitrary number of publishers to arbitrary number of subscribers.
  *
  * Topic has built-in back-pressure support implemented as maximum bound (`maxQueued`) that a subscriber is allowed to enqueue.
  * Once that bound is hit, publishing may semantically block until the lagging subscriber consumes some of its queued elements.
  *
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
    * If at any point, the queue backing the subscription has `maxQueued` elements in it,
    * any further publications semantically block until elements are dequeued from the
    * subscription queue.
    *
    * @param maxQueued maximum number of elements to enqueue to the subscription
    * queue before blocking publishers
    */
  def subscribe(maxQueued: Int): Stream[F, A]

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
    }
}

object Topic {

  /** Constructs a Topic */
  def apply[F[_], A](implicit F: Concurrent[F]): F[Topic[F, A]] = {
    // TODO is LongMap fine here, vs Map[Unique, Queue] ?
    // whichever way we go, standardise Topic and Signal
    case class State(subs: LongMap[InspectableQueue[F, A]], nextId: Long)

    val initialState = State(LongMap.empty, 1L)

    F.ref(initialState)
      .product(SignallingRef[F, Int](0))
      .map { case (state, subscriberCount) =>

        def mkSubscriber(maxQueued: Int): Resource[F, Stream[F, A]] =
          Resource.make {
            InspectableQueue.bounded[F, A](maxQueued).flatMap { q =>
              state.modify { case State(subs, id) =>
                State(subs + (id -> q), id + 1) -> (id, q)
              } <* subscriberCount.update(_ + 1)
            }
          } { case (id, _) =>
              state.update {
                case State(subs, nextId) => State(subs - id, nextId)
              } >> subscriberCount.update(_ - 1)
          }.map { case (_, q) => q.dequeue }

        new Topic[F, A] {
          def publish: Pipe[F, A, Unit] =
            _.flatMap(a => Stream.eval(publish1(a)))

          def subscribers: Stream[F, Int] = subscriberCount.discrete

          def publish1(a: A): F[Unit] =
            state.modify {
              case State(subs, nextId) =>
                val newState = State(subs, nextId)
                var publishToAll = F.unit
                subs.foreachValue { q =>
                  publishToAll = publishToAll >> q.enqueue1(a)
                }

                newState -> publishToAll
            }.flatten
             .uncancelable

          def subscribe(maxQueued: Int): Stream[F, A] =
            Stream.resource(mkSubscriber(maxQueued)).flatten
        }
      }
  }
}
