package scalaz.stream

/**
 * Represents an intermediate step of a `Process`, including any current
 * emitted values, a next state, and the current finalizer / cleanup `Process`.
 */
case class Step[+F[_],+A](
  head: Seq[A],
  tail: Process[F,A],
  cleanup: Process[F,A])
