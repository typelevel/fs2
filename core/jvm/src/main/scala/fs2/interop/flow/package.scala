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
package interop

import cats.effect.kernel.{Async, Resource}

import java.util.concurrent.Flow.{Publisher, Subscriber}

/** Implementation of the reactive-streams protocol for fs2; based on Java Flow.
  *
  * @example {{{
  * scala> import cats.effect.{IO, Resource}
  * scala> import fs2.Stream
  * scala> import fs2.interop.flow._
  * scala> import java.util.concurrent.Flow.Publisher
  * scala>
  * scala> val upstream: Stream[IO, Int] = Stream(1, 2, 3).covary[IO]
  * scala> val publisher: Resource[IO, Publisher[Int]] = upstream.toPublisher
  * scala> val downstream: Stream[IO, Int] = Stream.resource(publisher).flatMap { publisher =>
  *      |   publisher.toStream[IO](bufferSize = 16)
  *      | }
  * scala>
  * scala> import cats.effect.unsafe.implicits.global
  * scala> downstream.compile.toVector.unsafeRunSync()
  * res0: Vector[Int] = Vector(1, 2, 3)
  * }}}
  *
  * @see [[java.util.concurrent.Flow]]
  */
package object flow {

  /** Creates a [[Stream]] from a function exposing a [[Subscriber]].
    *
    * This function is useful when you actually need to provide a subscriber to a third-party.
    * The subscription process is uncancelable.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> import fs2.Stream
    * scala> import fs2.interop.flow.fromSubscriber
    * scala> import java.util.concurrent.Flow.Subscriber
    * scala>
    * scala> // This internally calls somePublisher.subscribe(subscriber)
    * scala> def thirdPartyLibrary(subscriber: Subscriber[Int]): Unit = ???
    * scala>
    * scala> // Interop with the third party library.
    * scala> fromSubscriber[IO, Int](bufferSize = 16) { subscriber =>
    *      |   IO.println("Subscribing!") >>
    *      |   IO.delay(thirdPartyLibrary(subscriber)) >>
    *      |   IO.println("Subscribed!")
    *      | }
    * res0: Stream[IO, Int] = Stream(..)
    * }}}
    *
    * @note The publisher only receives a subscriber when the stream is run.
    *
    * @see [[fromPublisher]] for a simpler version that only requires a [[Publisher]].
    *
    * @param bufferSize setup the number of elements asked each time from the [[Publisher]].
    *                   A high number can be useful if the publisher is triggering from IO,
    *                   like requesting elements from a database.
    *                   The publisher can use this `bufferSize` to query elements in batch.
    *                   A high number will also lead to more elements in memory.
    * @param onSubscribe The effectual function to run during the subscription process,
    *                    receives a [[Subscriber]] that should be used to subscribe to a [[Publisher]].
    *                    The `subscribe` operation must be called exactly once.
    */
  def fromSubscriber[F[_], A](
      bufferSize: Int
  )(
      subscribe: Subscriber[A] => F[Unit]
  )(implicit
      F: Async[F]
  ): Stream[F, A] =
    Stream
      .eval(StreamSubscriber[F, A](bufferSize))
      .flatMap { subscriber =>
        subscriber.stream(
          F.uncancelable(_ => subscribe(subscriber))
        )
      }

  /** Creates a [[Stream]] from an `onSubscribe` effectual function.
    *
    * This function is useful when you need to perform some effectual actions during the subscription process.
    * The subscription process is uncancelable.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> import fs2.Stream
    * scala> import fs2.interop.flow.fromOnSubscribe
    * scala> import java.util.concurrent.Flow.Publisher
    * scala>
    * scala> // Interop with the third party library.
    * scala> def thirdPartyPublisher(): Publisher[Int] = ???
    * scala> fromOnSubscribe[IO, Int](bufferSize = 16) { subscribe =>
    *      |   IO.println("Subscribing!") >>
    *      |   subscribe(thirdPartyPublisher()) >>
    *      |   IO.println("Subscribed!")
    *      | }
    * res0: Stream[IO, Int] = Stream(..)
    * }}}
    *
    * @note The publisher only receives a subscriber when the stream is run.
    *
    * @see [[fromPublisher]] for a simpler version that only requires a [[Publisher]].
    *
    * @param bufferSize setup the number of elements asked each time from the [[Publisher]].
    *                   A high number can be useful if the publisher is triggering from IO,
    *                   like requesting elements from a database.
    *                   The publisher can use this `bufferSize` to query elements in batch.
    *                   A high number will also lead to more elements in memory.
    * @param onSubscribe The effectual action to run during the subscription process,
    *                    represented as a function that receives as an argument
    *                    the actual `subscribe` operation; as another function receiving the [[Publisher]].
    *                    The `subscribe` operation must be called exactly once.
    */
  def fromOnSubscribe[F[_], A](
      bufferSize: Int
  )(
      onSubscribe: (Publisher[A] => F[Unit]) => F[Unit]
  )(implicit
      F: Async[F]
  ): Stream[F, A] =
    fromSubscriber[F, A](bufferSize) { subscriber =>
      val subscribe: Publisher[A] => F[Unit] =
        publisher => F.delay(publisher.subscribe(subscriber))

      onSubscribe(subscribe)
    }

  /** Creates a [[Stream]] from an [[Publisher]].
    *
    * @note The publisher only receives a subscriber when the stream is run.
    *
    * @param publisher The [[Publisher]] to transform.
    * @param bufferSize setup the number of elements asked each time from the [[Publisher]].
    *                   A high number can be useful if the publisher is triggering from IO,
    *                   like requesting elements from a database.
    *                   The publisher can use this `bufferSize` to query elements in batch.
    *                   A high number will also lead to more elements in memory.
    */
  def fromPublisher[F[_], A](
      publisher: Publisher[A],
      bufferSize: Int
  )(implicit
      F: Async[F]
  ): Stream[F, A] =
    fromOnSubscribe[F, A](bufferSize) { subscribe =>
      subscribe(publisher)
    }

  implicit final class PublisherOps[A](private val publisher: Publisher[A]) extends AnyVal {

    /** Creates a [[Stream]] from an [[Publisher]].
      *
      * @param bufferSize setup the number of elements asked each time from the [[Publisher]].
      *                   A high number can be useful is the publisher is triggering from IO,
      *                   like requesting elements from a database.
      *                   The publisher can use this `bufferSize` to query elements in batch.
      *                   A high number will also lead to more elements in memory.
      */
    def toStream[F[_]](bufferSize: Int)(implicit F: Async[F]): Stream[F, A] =
      flow.fromPublisher(publisher, bufferSize)
  }

  /** Creates a [[Publisher]] from a [[Stream]].
    *
    * The stream is only ran when elements are requested.
    * Closing the [[Resource]] means gracefully shutting down all active subscriptions.
    * Thus, no more elements will be published.
    *
    * @see [[subscribeStream]] for a simpler version that only requires a [[Subscriber]].
    *
    * @param stream The [[Stream]] to transform.
    */
  def toPublisher[F[_], A](
      stream: Stream[F, A]
  )(implicit
      F: Async[F]
  ): Resource[F, Publisher[A]] =
    StreamPublisher(stream)

  /** Allows subscribing a [[Subscriber]] to a [[Stream]].
    *
    * The returned program will run until
    * all the stream elements were consumed.
    * Cancelling this program will gracefully shutdown the subscription.
    *
    * @param stream the [[Stream]] that will be consumed by the subscriber.
    * @param subscriber the [[Subscriber]] that will receive the elements of the stream.
    */
  def subscribeStream[F[_], A](
      stream: Stream[F, A],
      subscriber: Subscriber[A]
  )(implicit
      F: Async[F]
  ): F[Unit] =
    StreamSubscription.subscribe(stream, subscriber)

  implicit final class StreamOps[F[_], A](private val stream: Stream[F, A]) extends AnyVal {

    /** Creates a [[Publisher]] from a [[Stream]].
      *
      * The stream is only ran when elements are requested.
      * Closing the [[Resource]] means gracefully shutting down all active subscriptions.
      * Thus, no more elements will be published.
      *
      * @see [[subscribe]] for a simpler version that only requires a [[Subscriber]].
      */
    def toPublisher(implicit F: Async[F]): Resource[F, Publisher[A]] =
      flow.toPublisher(stream)

    /** Subscribes the provided [[Subscriber]] to this stream.
      *
      * The returned program will run until
      * all the stream elements were consumed.
      * Cancelling this program will gracefully shutdown the subscription.
      *
      * @param subscriber the [[Subscriber]] that will receive the elements of the stream.
      */
    def subscribe(subscriber: Subscriber[A])(implicit F: Async[F]): F[Unit] =
      flow.subscribeStream(stream, subscriber)
  }

  /** Ensures a value is not null. */
  private[flow] def nonNull[A](a: A): Unit =
    if (a == null) {
      throw new NullPointerException()
    }
}
