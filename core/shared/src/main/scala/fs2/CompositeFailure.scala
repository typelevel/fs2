package fs2

import cats.data.NonEmptyList

/** Represents multiple (>1) exceptions were thrown. */
final class CompositeFailure(
    val head: Throwable,
    val tail: NonEmptyList[Throwable]
) extends Throwable(
      s"Multiple exceptions were thrown (${1 + tail.size}), first ${head.getClass.getName}: ${head.getMessage}",
      head) {

  /** Gets all causes (guaranteed to have at least 2 elements). */
  def all: NonEmptyList[Throwable] = head :: tail
}

object CompositeFailure {
  def apply(first: Throwable,
            second: Throwable,
            rest: List[Throwable] = List.empty): CompositeFailure =
    new CompositeFailure(first, NonEmptyList(second, rest))

  def fromList(errors: List[Throwable]): Throwable = errors match {
    case Nil                     => NoExceptionIsGoodException
    case hd :: Nil               => hd
    case first :: second :: rest => apply(first, second, rest)
  }

  /**
    * Builds composite failure from the results supplied.
    *
    * - When any result are on left, then the Left(err) is returned
    * - When both results fail, a CompositeFailure(_) is returned
    * - When both are NoExceptionIsGoodException, then that is returned
    *
    */
  def fromResults(first: Throwable, second: Throwable): Throwable =
    if (first eq NoExceptionIsGoodException)
      second
    else if (second eq NoExceptionIsGoodException)
      first
    else
      CompositeFailure(err, NonEmptyList(err1, Nil))

}
