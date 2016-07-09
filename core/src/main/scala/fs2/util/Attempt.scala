package fs2.util

object Attempt {

  /**
   * Evaluates the specified value and lifts the result in to an `Attempt` if evaluation completes
   * without throwing. If a non-fatal exception is thrown, it is returned wrapped in `Left`. Fatal
   * exceptions are thrown.
   */
  def apply[A](a: => A): Attempt[A] =
    try Right(a) catch { case NonFatal(t) => Left(t) }
}
