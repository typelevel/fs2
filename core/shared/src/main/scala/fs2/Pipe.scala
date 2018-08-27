package fs2

import cats.effect.Concurrent
import fs2.concurrent.{Queue, SignallingRef}

object Pipe {

  /** Queue based version of [[join]] that uses the specified queue. */
  def joinQueued[F[_], A, B](q: F[Queue[F, Option[Chunk[A]]]])(s: Stream[F, Pipe[F, A, B]])(
      implicit F: Concurrent[F]): Pipe[F, A, B] =
    in => {
      for {
        done <- Stream.eval(SignallingRef(false))
        q <- Stream.eval(q)
        b <- in.chunks
          .map(Some(_))
          .evalMap(q.enqueue1)
          .drain
          .onFinalize(q.enqueue1(None))
          .onFinalize(done.set(true))
          .merge(done.interrupt(s).flatMap { f =>
            f(q.dequeue.unNoneTerminate.flatMap { x =>
              Stream.chunk(x)
            })
          })
      } yield b
    }

  /** Asynchronous version of [[join]] that queues up to `maxQueued` elements. */
  def joinAsync[F[_]: Concurrent, A, B](maxQueued: Int)(
      s: Stream[F, Pipe[F, A, B]]): Pipe[F, A, B] =
    joinQueued[F, A, B](Queue.bounded(maxQueued))(s)

  /**
    * Joins a stream of pipes in to a single pipe.
    * Input is fed to the first pipe until it terminates, at which point input is
    * fed to the second pipe, and so on.
    */
  def join[F[_]: Concurrent, A, B](s: Stream[F, Pipe[F, A, B]]): Pipe[F, A, B] =
    joinQueued[F, A, B](Queue.synchronous)(s)
}
