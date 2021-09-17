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

import cats.effect._
import cats.effect.implicits._
import cats.syntax.all._
import scala.collection.immutable.LongMap

/** Topic allows you to distribute `A`s published by an arbitrary number of publishers to an
  * arbitrary number of subscribers.
  *
  * Topic has built-in back-pressure support implemented as the maximum number of elements
  * (`maxQueued`) that a subscriber is allowed to enqueue.
  *
  * Once that bound is hit, any publishing action will semantically block until the lagging
  * subscriber consumes some of its queued elements.
  */
abstract class Topic[F[_], A] { self =>

  /** Publishes elements from source of `A` to this topic. [[Pipe]] equivalent of `publish1`. Closes
    * the topic when the input stream terminates. Especially useful when the topic has a single
    * producer.
    */
  def publish: Pipe[F, A, Nothing]

  /** Publishes one `A` to topic. No-op if the channel is closed, see [[close]] for further info.
    *
    * This operation does not complete until after the given element has been enqued on all
    * subscribers, which means that if any subscriber is at its `maxQueued` limit, `publish1` will
    * semantically block until that subscriber consumes an element.
    *
    * A semantically blocked publication can be interrupted, but there is no guarantee of atomicity,
    * and it could result in the `A` being received by some subscribers only.
    *
    * Note: if `publish1` is called concurrently by multiple producers, different subscribers may
    * receive messages from different producers in a different order.
    */
  def publish1(a: A): F[Either[Topic.Closed, Unit]]

  /** Subscribes for `A` values that are published to this topic.
    *
    * Pulling on the returned stream opens a "subscription", which allows up to `maxQueued` elements
    * to be enqueued as a result of publication.
    *
    * If at any point, the queue backing the subscription has `maxQueued` elements in it, any
    * further publications semantically block until elements are dequeued from the subscription
    * queue.
    *
    * @param maxQueued
    *   maximum number of elements to enqueue to the subscription queue before blocking publishers
    */
  def subscribe(maxQueued: Int): Stream[F, A]

  /** Like `subscribe`, but represents the subscription explicitly as a `Resource` which returns
    * after the subscriber is subscribed, but before it has started pulling elements.
    */
  def subscribeAwait(maxQueued: Int): Resource[F, Stream[F, A]]

  /** Signal of current active subscribers.
    */
  def subscribers: Stream[F, Int]

  /** This method achieves graceful shutdown: when the topics gets closed, its subscribers will
    * terminate naturally after consuming all currently enqueued elements.
    *
    * "Termination" here means that subscribers no longer wait for new elements on the topic, and
    * not that they will be interrupted while performing another action: if you want to interrupt a
    * subscriber, without first processing enqueued elements, you should use `interruptWhen` on it
    * instead.
    *
    * After a call to `close`, any further calls to `publish1` or `close` will be no-ops.
    *
    * Note that `close` does not automatically unblock producers which might be blocked on a bound,
    * they will only become unblocked if/when subscribers naturally finish to consume the respective
    * elements. You can `race` the publish with `close` to interrupt them immediately.
    */
  def close: F[Either[Topic.Closed, Unit]]

  /** Returns true if this topic is closed */
  def isClosed: F[Boolean]

  /** Semantically blocks until the topic gets closed. */
  def closed: F[Unit]

  /** Returns an alternate view of this `Topic` where its elements are of type `B`, given two
    * functions, `A => B` and `B => A`.
    */
  def imap[B](f: A => B)(g: B => A): Topic[F, B] =
    new Topic[F, B] {
      def publish: Pipe[F, B, Nothing] = sfb => self.publish(sfb.map(g))
      def publish1(b: B): F[Either[Topic.Closed, Unit]] = self.publish1(g(b))
      def subscribe(maxQueued: Int): Stream[F, B] =
        self.subscribe(maxQueued).map(f)
      def subscribeAwait(maxQueued: Int): Resource[F, Stream[F, B]] =
        self.subscribeAwait(maxQueued).map(_.map(f))
      def subscribers: Stream[F, Int] = self.subscribers
      def close: F[Either[Topic.Closed, Unit]] = self.close
      def isClosed: F[Boolean] = self.isClosed
      def closed: F[Unit] = self.closed
    }
}

object Topic {
  type Closed = Closed.type
  object Closed

  /** Constructs a Topic */
  def apply[F[_], A](implicit F: Concurrent[F]): F[Topic[F, A]] =
    (
      F.ref(LongMap.empty[Channel[F, A]] -> 1L),
      SignallingRef[F, Int](0),
      F.deferred[Unit]
    ).mapN { case (state, subscriberCount, signalClosure) =>
      new Topic[F, A] {

        def foreach[B](lm: LongMap[B])(f: B => F[Unit]) =
          lm.foldLeft(F.unit) { case (op, (_, b)) => op >> f(b) }

        def publish1(a: A): F[Either[Topic.Closed, Unit]] =
          signalClosure.tryGet.flatMap {
            case Some(_) => Topic.closed.pure[F]
            case None =>
              state.get
                .flatMap { case (subs, _) => foreach(subs)(_.send(a).void) }
                .as(Topic.rightUnit)
          }

        def subscribeAwait(maxQueued: Int): Resource[F, Stream[F, A]] =
          Resource
            .eval(Channel.bounded[F, A](maxQueued))
            .flatMap { chan =>
              val subscribe = state.modify { case (subs, id) =>
                (subs.updated(id, chan), id + 1) -> id
              } <* subscriberCount.update(_ + 1)

              def unsubscribe(id: Long) =
                state.modify { case (subs, nextId) =>
                  // _After_ we remove the bounded channel for this
                  // subscriber, we need to drain it to unblock to
                  // publish loop which might have already enqueued
                  // something.
                  def drainChannel: F[Unit] =
                    subs.get(id).traverse_ { chan =>
                      chan.close >> chan.stream.compile.drain
                    }

                  (subs - id, nextId) -> drainChannel
                }.flatten >> subscriberCount.update(_ - 1)

              Resource
                .make(subscribe)(unsubscribe)
                .as(chan.stream)
            }

        def publish: Pipe[F, A, INothing] = { in =>
          (in ++ Stream.exec(close.void))
            .evalMap(publish1)
            .takeWhile(_.isRight)
            .drain
        }

        def subscribe(maxQueued: Int): Stream[F, A] =
          Stream.resource(subscribeAwait(maxQueued)).flatten

        def subscribers: Stream[F, Int] = subscriberCount.discrete

        def close: F[Either[Topic.Closed, Unit]] =
          signalClosure
            .complete(())
            .flatMap { completedNow =>
              val result = if (completedNow) Topic.rightUnit else Topic.closed

              state.get
                .flatMap { case (subs, _) => foreach(subs)(_.close.void) }
                .as(result)
            }
            .uncancelable

        def closed: F[Unit] = signalClosure.get
        def isClosed: F[Boolean] = signalClosure.tryGet.map(_.isDefined)
      }
    }

  private final val closed: Either[Closed, Unit] = Left(Closed)
  private final val rightUnit: Either[Closed, Unit] = Right(())
}
