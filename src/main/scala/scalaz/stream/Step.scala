package scalaz.stream

import scalaz.{\/-, -\/, \/}
import scalaz.stream.Process._

/**
 * Represents an intermediate step of a `Process`, including any current
 * emitted values, a next state, and the current finalizer / cleanup `Process`.
 */
case class Step[+F[_],+A](
  head: Throwable \/ Seq[A],
  tail: Process[F,A],
  cleanup: Process[F,A]) {
  def headOption: Option[Seq[A]] = head.toOption

  def fold[R](success: Seq[A] => R)(fallback: => R, error: => R): R =
    head.fold(e => if (e == Process.End) fallback else error, success)
}

object Step {
  def failed(e:Throwable) = Step(-\/(e),Halt(e),halt)
  def done = Step(-\/(End),halt,halt)
}