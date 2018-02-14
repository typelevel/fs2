package fs2

import fs2.internal.FreeC

/**
  * Algebra expressing merging of the streams.
  */
sealed trait Tee[F[_], L, R, O, X]

object Tee {

  final case class PullLeft[F[_], L, R, O]() extends Tee[F, L, R, O, Option[Segment[L, Unit]]]
  final case class PullRight[F[_], L, R, O]() extends Tee[F, L, R, O, Option[Segment[R, Unit]]]
  final case class Output[F[_], L, R, O](segment: Segment[O, Unit]) extends Tee[F, L, R, O, Unit]
  final case class Eval[F[_], L, R, O, X](f: F[X]) extends Tee[F, L, R, O, X]

  private val _pull_left =
    FreeC.Eval[Tee[Nothing, Nothing, Nothing, Nothing, ?], Option[Segment[Nothing, Unit]]](
      PullLeft[Nothing, Nothing, Nothing, Nothing])

  private val _pull_right =
    FreeC.Eval[Tee[Nothing, Nothing, Nothing, Nothing, ?], Option[Segment[Nothing, Unit]]](
      PullRight[Nothing, Nothing, Nothing, Nothing])

  /** pulls from left side. Yields to None if left side finished **/
  def pullLeft[F[_], L, R, O]: FreeC[Tee[F, L, R, O, ?], Option[Segment[L, Unit]]] =
    _pull_left.asInstanceOf[FreeC[Tee[F, L, R, O, ?], Option[Segment[L, Unit]]]]

  def pullRight[F[_], L, R, O]: FreeC[Tee[F, L, R, O, ?], Option[Segment[R, Unit]]] =
    _pull_right.asInstanceOf[FreeC[Tee[F, L, R, O, ?], Option[Segment[R, Unit]]]]

}
