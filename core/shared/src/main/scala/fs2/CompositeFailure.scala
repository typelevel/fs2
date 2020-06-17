package fs2

import cats.data.NonEmptyList

/** Represents multiple (>1) exceptions were thrown. */
final class CompositeFailure(
    val head: Throwable,
    val tail: NonEmptyList[Throwable]
) extends Throwable(
      s"Multiple exceptions were thrown (${1 + tail.size}), first ${head.getClass.getName}: ${head.getMessage}",
      head
    ) {

  /** Gets all causes (guaranteed to have at least 2 elements). */
  def all: NonEmptyList[Throwable] = head :: tail
}

object CompositeFailure {
  def apply(
      first: Throwable,
      second: Throwable,
      rest: List[Throwable] = List.empty
  ): CompositeFailure =
    apply(first, NonEmptyList(second, rest))

  def apply(first: Throwable, rest: NonEmptyList[Throwable]): CompositeFailure = {
    def flattenExceptions(throwable: Throwable): NonEmptyList[Throwable] =
      throwable match {
        case cf: CompositeFailure => cf.all
        case throwable            => NonEmptyList.one(throwable)
      }
    first match {
      case cf: CompositeFailure =>
        val tail = cf.tail ::: rest.flatMap(flattenExceptions)
        new CompositeFailure(cf.head, tail)
      case _ =>
        new CompositeFailure(first, rest.flatMap(flattenExceptions))
    }
  }

  def fromList(errors: List[Throwable]): Option[Throwable] =
    errors match {
      case Nil                     => None
      case hd :: Nil               => Some(hd)
      case first :: second :: rest => Some(apply(first, second, rest))
    }

  /**
    * Builds composite failure from the results supplied.
    *
    * - When any of the results are on left, then the Left(err) is returned
    * - When both results fail, the Left(CompositeFailure(_)) is returned
    * - When both results succeeds then Right(()) is returned
    */
  def fromResults(
      first: Either[Throwable, Unit],
      second: Either[Throwable, Unit]
  ): Either[Throwable, Unit] =
    first match {
      case Right(_) => second
      case Left(err) =>
        Left(second.fold(err1 => apply(err, err1, Nil), _ => err))
    }
}
