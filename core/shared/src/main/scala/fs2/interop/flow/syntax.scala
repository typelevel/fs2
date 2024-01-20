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

import cats.effect.kernel.{Async, Resource}

import java.util.concurrent.Flow.{Publisher, Subscriber}

object syntax {
  implicit final class PublisherOps[A](private val publisher: Publisher[A]) extends AnyVal {

    @deprecated("Use Stream.fromPublisher", "3.9.4")
    def toStream[F[_]](chunkSize: Int)(implicit F: Async[F]): Stream[F, A] =
      flow.fromPublisher(publisher, chunkSize)
  }

  implicit final class StreamOps[F[_], A](private val stream: Stream[F, A]) extends AnyVal {

    @deprecated("Use Stream#toPublisherResource", "3.9.4")
    def toPublisher(implicit F: Async[F]): Resource[F, Publisher[A]] =
      flow.toPublisher(stream)

    @deprecated("Use Stream#subscribe", "3.9.4")
    def subscribe(subscriber: Subscriber[A])(implicit F: Async[F]): F[Unit] =
      flow.subscribeStream(stream, subscriber)
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
