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
      strategy: PubSub.Strategy[Option[A], Option[Chunk[A]], S, Int])
    : F[fs2.concurrent.NoneTerminatedQueue[F, A]] =
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

    def unbounded[A](append: (SizedQueue[A], A) => SizedQueue[A])
      : PubSub.Strategy[A, Chunk[A], SizedQueue[A], Int] =
      PubSub.Strategy.convert(fs2.concurrent.Queue.Strategy.unbounded(append))

  }

}
