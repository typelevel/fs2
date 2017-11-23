package fs2

import cats.data.NonEmptyList

/**
  * Certain implementation in fs2 may cause multiple failures from the different part of the code to be thrown and collected.
  * In these scenarios, COmposite failure assures that user won't lose any intermediate failures.
  */
class CompositeFailure(
  failures: NonEmptyList[Throwable]
) extends Throwable({
  if (failures.size == 1) failures.head.getMessage
  else s"Multiple exceptions were thrown (${failures.size}), first ${failures.head.getClass.getName}: ${failures.head.getMessage}"
}, failures.head)
