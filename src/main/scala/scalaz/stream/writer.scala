package  scalaz.stream

import scalaz.\/._


object writer {

  /** Promote a `Process` to a `Writer` that outputs nothing. */
  def liftW[F[_], W](p: Process[F, W]): Writer[F, W, Nothing] =
    p.map(left)

  /** Promote a `Process` to a `Writer` that writes nothing. */
  def liftO[F[_], O](p: Process[F, O]): Writer[F, Nothing, O] =
    p.map(right)

  /**
   * Promote a `Process` to a `Writer` that writes and outputs
   * all values of `p`.
   */
  def logged[F[_], A](p: Process[F, A]): Writer[F, A, A] =
    p.flatMap(a => Process.emitAll(Vector(left(a), right(a))))

}
