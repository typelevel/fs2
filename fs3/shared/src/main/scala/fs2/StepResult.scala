package fs2

/** Result of stepping a pull. */
private[fs2] sealed abstract class StepResult[F[_], O, R]

private[fs2] object StepResult {

  /** The step reached the end of the pull. */
  final case class Done[F[_], O, R](result: R) extends StepResult[F, O, R]

  /** The step output a chunk of elements and has a subsequent tail pull. */
  final case class Output[F[_], O, R](head: Chunk[O], tail: Pull[F, O, R])
      extends StepResult[F, O, R]

  /** The step was interrupted. */
  final case class Interrupted[F[_], O, R](err: Option[Throwable]) extends StepResult[F, O, R]

  def done[F[_], O, R](result: R): StepResult[F, O, R] = Done(result)
  def output[F[_], O, R](head: Chunk[O], tail: Pull[F, O, R]): StepResult[F, O, R] =
    Output(head, tail)
  def interrupted[F[_], O, R](err: Option[Throwable]): StepResult[F, O, R] =
    Interrupted(err)
}
