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

import cats.effect.IO
import cats.effect.kernel.{Async, Resource}
import cats.effect.unsafe.IORuntime

import java.util.concurrent.Flow.{Publisher, Subscriber, defaultBufferSize}

/** Implementation of the reactive-streams protocol for fs2; based on Java Flow.
  *
  * @example {{{
  * scala> import cats.effect.{IO, Resource}
  * scala> import fs2.Stream
  * scala> import fs2.interop.flow.syntax._
  * scala> import java.util.concurrent.Flow.Publisher
  * scala>
  * scala> val upstream: Stream[IO, Int] = Stream(1, 2, 3).covary[IO]
  * scala> val publisher: Resource[IO, Publisher[Int]] = upstream.toPublisher
  * scala> val downstream: Stream[IO, Int] = Stream.resource(publisher).flatMap { publisher =>
  *      |   publisher.toStream[IO](chunkSize = 16)
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

  /** Creates a [[Stream]] from a `subscribe` function;
    * analogous to a `Publisher`, but effectual.
    *
    * This function is useful when you actually need to provide a subscriber to a third-party.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> import fs2.Stream
    * scala> import java.util.concurrent.Flow.{Publisher, Subscriber}
    * scala>
    * scala> def thirdPartyLibrary(subscriber: Subscriber[Int]): Unit = {
    *      |  def somePublisher: Publisher[Int] = ???
    *      |  somePublisher.subscribe(subscriber)
    *      | }
    * scala>
    * scala> // Interop with the third party library.
    * scala> fs2.interop.flow.fromPublisher[IO, Int](chunkSize = 16) { subscriber =>
    *      |   IO.println("Subscribing!") >>
    *      |   IO.delay(thirdPartyLibrary(subscriber)) >>
    *      |   IO.println("Subscribed!")
    *      | }
    * res0: Stream[IO, Int] = Stream(..)
    * }}}
    *
    * @note The subscribe function will not be executed until the stream is run.
    *
    * @see the overload that only requires a [[Publisher]].
    *
    * @param chunkSize setup the number of elements asked each time from the [[Publisher]].
    *                  A high number may be useful if the publisher is triggering from IO,
    *                  like requesting elements from a database.
    *                  A high number will also lead to more elements in memory.
    *                  The stream will not emit new element until,
    *                  either the `Chunk` is filled or the publisher finishes.
    * @param subscribe The effectual function that will be used to initiate the consumption process,
    *                  it receives a [[Subscriber]] that should be used to subscribe to a [[Publisher]].
    *                  The `subscribe` operation must be called exactly once.
    */
  def fromPublisher[F[_], A](
      chunkSize: Int
  )(
      subscribe: Subscriber[A] => F[Unit]
  )(implicit
      F: Async[F]
  ): Stream[F, A] =
    Stream
      .eval(StreamSubscriber[F, A](chunkSize))
      .flatMap { subscriber =>
        subscriber.stream(subscribe(subscriber))
      }

  /** Creates a [[Stream]] from a [[Publisher]].
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> import fs2.Stream
    * scala> import java.util.concurrent.Flow.Publisher
    * scala>
    * scala> def getThirdPartyPublisher(): Publisher[Int] = ???
    * scala>
    * scala> // Interop with the third party library.
    * scala> Stream.eval(IO.delay(getThirdPartyPublisher())).flatMap { publisher =>
    *      |   fs2.interop.flow.fromPublisher[IO](publisher, chunkSize = 16)
    *      | }
    * res0: Stream[IO, Int] = Stream(..)
    * }}}
    *
    * @note The [[Publisher]] will not receive a [[Subscriber]] until the stream is run.
    *
    * @see the `toStream` extension method added to `Publisher`
    *
    * @param publisher The [[Publisher]] to consume.
    * @param chunkSize setup the number of elements asked each time from the [[Publisher]].
    *                  A high number may be useful if the publisher is triggering from IO,
    *                  like requesting elements from a database.
    *                  A high number will also lead to more elements in memory.
    *                  The stream will not emit new element until,
    *                  either the `Chunk` is filled or the publisher finishes.
    */
  def fromPublisher[F[_]]: syntax.FromPublisherPartiallyApplied[F] =
    new syntax.FromPublisherPartiallyApplied(dummy = true)

  /** Creates a [[Publisher]] from a [[Stream]].
    *
    * The stream is only ran when elements are requested.
    * Closing the [[Resource]] means not accepting new subscriptions,
    * but waiting for all active ones to finish consuming.
    * Canceling the [[Resource.use]] means gracefully shutting down all active subscriptions.
    * Thus, no more elements will be published.
    *
    * @note This [[Publisher]] can be reused for multiple [[Subscribers]],
    *       each [[Subscription]] will re-run the [[Stream]] from the beginning.
    *
    * @see [[unsafeToPublisher]] for an unsafe version that returns a plain [[Publisher]].
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

  /** Creates a [[Publisher]] from a [[Stream]].
    *
    * The stream is only ran when elements are requested.
    *
    * @note This [[Publisher]] can be reused for multiple [[Subscribers]],
    *       each [[Subscription]] will re-run the [[Stream]] from the beginning.
    *
    * @see [[toPublisher]] for a safe version that returns a [[Resource]].
    *
    * @param stream The [[Stream]] to transform.
    */
  def unsafeToPublisher[A](
      stream: Stream[IO, A]
  )(implicit
      runtime: IORuntime
  ): Publisher[A] =
    StreamPublisher.unsafe(stream)

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

  /** A default value for the `chunkSize` argument,
    * that may be used in the absence of other constraints;
    * we encourage choosing an appropriate value consciously.
    * Alias for [[defaultBufferSize]].
    *
    * @note the current value is `256`.
    */
  val defaultChunkSize: Int = defaultBufferSize
}
