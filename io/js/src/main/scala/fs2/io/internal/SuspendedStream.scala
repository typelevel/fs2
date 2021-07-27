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
package io
package internal

import cats.effect.kernel.Concurrent
import cats.effect.kernel.Resource
import cats.effect.std.Queue
import cats.effect.std.Semaphore
import cats.effect.syntax.all._

private[io] trait SuspendedStream[F[_], O] {

  def stream: Stream[F, O]

  def getAndUpdate[O2](f: Stream[F, O] => Pull[F, O2, Stream[F, O]]): Stream[F, O2]

}

private[io] object SuspendedStream {
  def apply[F[_], O](
      stream: Stream[F, O]
  )(implicit F: Concurrent[F]): Resource[F, SuspendedStream[F, O]] =
    for {
      queue <- Queue.synchronous[F, Option[Either[Throwable, Chunk[O]]]].toResource
      _ <- stream.chunks.attempt.enqueueNoneTerminated(queue).compile.drain.background
      suspended <- F.ref(streamFromQueue(queue)).toResource
      semaphore <- Semaphore[F](1).toResource
    } yield new SuspendedStream[F, O] {

      override def stream: Stream[F, O] = Stream.resource(semaphore.permit).flatMap { _ =>
        Stream.eval(suspended.get).flatten.onFinalize(suspended.set(streamFromQueue(queue)))
      }

      override def getAndUpdate[O2](f: Stream[F, O] => Pull[F, O2, Stream[F, O]]): Stream[F, O2] =
        Stream.resource(semaphore.permit).flatMap { _ =>
          Pull
            .eval(suspended.get)
            .flatMap(f)
            .flatMap { tail =>
              Pull.eval(suspended.set(tail)).void
            }
            .stream
        }
    }

  private def streamFromQueue[F[_]: Concurrent, O](
      queue: Queue[F, Option[Either[Throwable, Chunk[O]]]]
  ): Stream[F, O] =
    Stream.fromQueueNoneTerminated(queue).rethrow.unchunks
}
