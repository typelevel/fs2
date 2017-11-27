package fs2

import cats.data.NonEmptyList

/** Represents multiple (>1) exceptions were thrown. */
final class CompositeFailure(
  head: Throwable,
  tail: NonEmptyList[Throwable]
) extends Throwable(s"Multiple exceptions were thrown (${1 + tail.size}), first ${head.getClass.getName}: ${head.getMessage}", head)

object CompositeFailure {
  def apply(first: Throwable, second: Throwable, rest: List[Throwable]): CompositeFailure =
    new CompositeFailure(first, NonEmptyList(second, rest))

  def fromList(errors: List[Throwable]): Option[Throwable] = errors match {
    case Nil => None
    case hd :: Nil => Some(hd)
    case first :: second :: rest => Some(apply(first, second, rest))
  }
}
