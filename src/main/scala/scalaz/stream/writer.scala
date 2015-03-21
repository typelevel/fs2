package  scalaz.stream

import scalaz.\/._


object writer {

  /** Promote a `Process` to a `Writer` that writes nothing. */
  def liftW[F[_], A](p: Process[F, A]): Writer[F, Nothing, A] =
    p.map(right)

  /**
   * Promote a `Process` to a `Writer` that writes and outputs
   * all values of `p`.
   */
  def logged[F[_], A](p: Process[F, A]): Writer[F, A, A] =
    p.flatMap(a => Process.emitAll(Vector(left(a), right(a))))

}

