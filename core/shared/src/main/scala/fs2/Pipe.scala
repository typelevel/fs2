package fs2

import cats.effect.Concurrent

object Pipe {

  /**
    * Joins a stream of pipes in to a single pipe.
    * Input is fed to the first pipe until it terminates, at which point input is
    * fed to the second pipe, and so on.
    */
  def join[F[_]: Concurrent, A, B](pipes: Stream[F, Pipe[F, A, B]]): Pipe[F, A, B] =
    _.broadcast.zipWith(pipes)(_.through(_)).flatten
}
