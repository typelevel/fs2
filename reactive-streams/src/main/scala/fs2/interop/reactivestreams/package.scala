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

import cats.effect.kernel._
import org.reactivestreams._

/** Implementation of the reactivestreams protocol for fs2
  *
  * @example {{{
  * scala> import fs2._
  * scala> import fs2.interop.reactivestreams._
  * scala> import cats.effect.{IO, Resource}, cats.effect.unsafe.implicits.global
  * scala>
  * scala> val upstream: Stream[IO, Int] = Stream(1, 2, 3).covary[IO]
  * scala> val publisher: Resource[IO, StreamUnicastPublisher[IO, Int]] = upstream.toUnicastPublisher
  * scala> val downstream: Stream[IO, Int] = Stream.resource(publisher).flatMap(_.toStreamBuffered[IO](bufferSize = 16))
  * scala>
  * scala> downstream.compile.toVector.unsafeRunSync()
  * res0: Vector[Int] = Vector(1, 2, 3)
  * }}}
  *
  * @see [[http://www.reactive-streams.org/]]
  *
  * @deprecated
  *   The next major version of fs2 will drop these converters.
  *   Rather, users will be encouraged to use the new [[fs2.interop.flow]] package,
  *   which provides support for the `java.util.concurrent.Flow` types;
  *   that superseded the `reactive-streams` library.
  *   In case you need to interop with a library that only provides `reactive-streams` types,
  *   you may use `org.reactivestreams.FlowAdapters`: [[https://www.reactive-streams.org/reactive-streams-flow-adapters-1.0.2-javadoc/org/reactivestreams/FlowAdapters.html]]
  */
package object reactivestreams {

  /** Creates a lazy stream from an `org.reactivestreams.Publisher`.
    *
    * The publisher only receives a subscriber when the stream is run.
    *
    * @param bufferSize setup the number of elements asked each time from the `org.reactivestreams.Publisher`.
    *                   A high number can be useful if the publisher is triggering from IO, like requesting elements from a database.
    *                   The publisher can use this `bufferSize` to query elements in batch.
    *                   A high number will also lead to more elements in memory.
    */
  def fromPublisher[F[_]: Async, A](p: Publisher[A], bufferSize: Int): Stream[F, A] =
    Stream
      .eval(StreamSubscriber[F, A](bufferSize))
      .flatMap(s => s.sub.stream(Sync[F].delay(p.subscribe(s))))

  /** Creates a lazy stream from an `org.reactivestreams.Publisher`.
    *
    * The publisher only receives a subscriber when the stream is run.
    */
  @deprecated(
    "Use fromPublisher method with a buffer size. Use a buffer size of 1 to keep the same behavior.",
    "3.1.4"
  )
  def fromPublisher[F[_]: Async, A](p: Publisher[A]): Stream[F, A] =
    Stream
      .eval(StreamSubscriber[F, A])
      .flatMap(s => s.sub.stream(Sync[F].delay(p.subscribe(s))))

  implicit final class PublisherOps[A](val publisher: Publisher[A]) extends AnyVal {

    /** Creates a lazy stream from an `org.reactivestreams.Publisher`
      *
      * @param bufferSize setup the number of elements asked each time from the `org.reactivestreams.Publisher`.
      *                   A high number can be useful is the publisher is triggering from IO, like requesting elements from a database.
      *                   The publisher can use this `bufferSize` to query elements in batch.
      *                   A high number will also lead to more elements in memory.
      */
    def toStreamBuffered[F[_]: Async](bufferSize: Int): Stream[F, A] =
      fromPublisher(publisher, bufferSize)

    /** Creates a lazy stream from an `org.reactivestreams.Publisher` */
    @deprecated(
      "Use toStreamBuffered method instead. Use a buffer size of 1 to keep the same behavior.",
      "3.1.4"
    )
    def toStream[F[_]: Async]: Stream[F, A] =
      fromPublisher(publisher)
  }

  /** Allows subscribing a `org.reactivestreams.Subscriber` to a [[Stream]].
    *
    * The returned program will run until
    * all the stream elements were consumed.
    * Cancelling this program will gracefully shutdown the subscription.
    *
    * @param stream the [[Stream]] that will be consumed by the subscriber.
    * @param subscriber the Subscriber that will receive the elements of the stream.
    */
  def subscribeStream[F[_], A](stream: Stream[F, A], subscriber: Subscriber[A])(implicit
      F: Async[F]
  ): F[Unit] =
    StreamSubscription.subscribe(stream, subscriber)

  implicit final class StreamOps[F[_], A](val stream: Stream[F, A]) {

    /** Creates a [[StreamUnicastPublisher]] from a [[Stream]].
      *
      * The stream is only ran when elements are requested.
      *
      * @note Not longer unicast, this Publisher can be reused for multiple Subscribers:
      *       each subscription will re-run the [[Stream]] from the beginning.
      */
    def toUnicastPublisher(implicit
        F: Async[F]
    ): Resource[F, StreamUnicastPublisher[F, A]] =
      StreamUnicastPublisher(stream)

    /** Subscribes the provided `org.reactivestreams.Subscriber` to this stream.
      *
      * @param subscriber the Subscriber that will receive the elements of the stream.
      */
    def subscribe(subscriber: Subscriber[A])(implicit F: Async[F]): F[Unit] =
      reactivestreams.subscribeStream(stream, subscriber)
  }
}
