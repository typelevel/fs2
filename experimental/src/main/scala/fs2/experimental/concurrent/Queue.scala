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

package fs2.experimental.concurrent

import cats.effect.Concurrent
import fs2.Chunk
import fs2.internal.SizedQueue

object Queue {
  def forStrategy[F[_]: Concurrent, S, A](
      strategy: PubSub.Strategy[A, Chunk[A], S, Int]
  ): F[fs2.concurrent.Queue[F, A]] =
    fs2.concurrent.Queue.in[F].forStrategy(strategy)

  private[fs2] def forStrategyNoneTerminated[F[_]: Concurrent, S, A](
      strategy: PubSub.Strategy[Option[A], Option[Chunk[A]], S, Int]
  ): F[fs2.concurrent.NoneTerminatedQueue[F, A]] =
    fs2.concurrent.Queue.in[F].forStrategyNoneTerminated(strategy)

  object Strategy {
    def boundedFifo[A](maxSize: Int): PubSub.Strategy[A, Chunk[A], SizedQueue[A], Int] =
      PubSub.Strategy.convert(fs2.concurrent.Queue.Strategy.boundedFifo(maxSize))

    def boundedLifo[A](maxSize: Int): PubSub.Strategy[A, Chunk[A], SizedQueue[A], Int] =
      PubSub.Strategy.convert(fs2.concurrent.Queue.Strategy.boundedLifo(maxSize))

    def circularBuffer[A](maxSize: Int): PubSub.Strategy[A, Chunk[A], SizedQueue[A], Int] =
      PubSub.Strategy.convert(fs2.concurrent.Queue.Strategy.circularBuffer(maxSize))

    def fifo[A]: PubSub.Strategy[A, Chunk[A], SizedQueue[A], Int] =
      PubSub.Strategy.convert(fs2.concurrent.Queue.Strategy.fifo)

    def lifo[A]: PubSub.Strategy[A, Chunk[A], SizedQueue[A], Int] =
      PubSub.Strategy.convert(fs2.concurrent.Queue.Strategy.lifo)

    def synchronous[A]: PubSub.Strategy[A, Chunk[A], (Boolean, Option[A]), Int] =
      PubSub.Strategy.convert(fs2.concurrent.Queue.Strategy.synchronous)

    def unbounded[A](
        append: (SizedQueue[A], A) => SizedQueue[A]
    ): PubSub.Strategy[A, Chunk[A], SizedQueue[A], Int] =
      PubSub.Strategy.convert(fs2.concurrent.Queue.Strategy.unbounded(append))
  }
}
