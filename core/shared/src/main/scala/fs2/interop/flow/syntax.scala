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
package flow

import cats.effect.IO
import cats.effect.kernel.{Async, Resource}
import cats.effect.unsafe.IORuntime

import java.util.concurrent.Flow.{Publisher, Subscriber}

object syntax {
  implicit final class PublisherOps[A](private val publisher: Publisher[A]) extends AnyVal {

    /** Creates a [[Stream]] from a [[Publisher]].
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> import fs2.Stream
      * scala> import fs2.interop.flow.syntax._
      * scala> import java.util.concurrent.Flow.Publisher
      * scala>
      * scala> def getThirdPartyPublisher(): Publisher[Int] = ???
      * scala>
      * scala> // Interop with the third party library.
      * scala> Stream.eval(IO.delay(getThirdPartyPublisher())).flatMap { publisher =>
      *      |   publisher.toStream[IO](chunkSize = 16)
      *      | }
      * res0: Stream[IO, Int] = Stream(..)
      * }}}
      *
      * @note The [[Publisher]] will not receive a [[Subscriber]] until the stream is run.
      *
      * @param chunkSize setup the number of elements asked each time from the [[Publisher]].
      *                  A high number may be useful if the publisher is triggering from IO,
      *                  like requesting elements from a database.
      *                  A high number will also lead to more elements in memory.
      *                  The stream will not emit new element until,
      *                  either the `Chunk` is filled or the publisher finishes.
      */
    def toStream[F[_]](chunkSize: Int)(implicit F: Async[F]): Stream[F, A] =
      flow.fromPublisher(publisher, chunkSize)
  }

  implicit final class StreamOps[F[_], A](private val stream: Stream[F, A]) extends AnyVal {

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

  implicit final class StreamIOOps[A](private val stream: Stream[IO, A]) extends AnyVal {

    /** Creates a [[Publisher]] from a [[Stream]].
      *
      * The stream is only ran when elements are requested.
      *
      * @note This [[Publisher]] can be reused for multiple [[Subscribers]],
      *       each [[Subscription]] will re-run the [[Stream]] from the beginning.
      *
      * @see [[toPublisher]] for a safe version that returns a [[Resource]].
      */
    def unsafeToPublisher()(implicit
        runtime: IORuntime
    ): Publisher[A] =
      flow.unsafeToPublisher(stream)
  }

  final class FromPublisherPartiallyApplied[F[_]](private val dummy: Boolean) extends AnyVal {
    def apply[A](
        publisher: Publisher[A],
        chunkSize: Int
    )(implicit
        F: Async[F]
    ): Stream[F, A] =
      fromPublisher[F, A](chunkSize) { subscriber =>
        F.delay(publisher.subscribe(subscriber))
      }
  }
}
