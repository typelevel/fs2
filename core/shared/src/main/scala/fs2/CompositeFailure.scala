package fs2

/**
  * Certain implementation in fs2 may cause multiple failures from the different part of the code to be thrown and collected.
  * In these scenarios, COmposite failure assures that user won't lose any intermediate failures.
  */
class CompositeFailure(
  first: Throwable
  , others: List[Throwable]
) extends Throwable(s"Resources failed to finalize: [${first.getClass.getName}: ${first.getMessage}] + ${others.size} others", first)
