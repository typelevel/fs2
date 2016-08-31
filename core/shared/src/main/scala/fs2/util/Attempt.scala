package fs2.util

/** Provides constructors for the `Attempt` alias. */
object Attempt {

  /**
   * Evaluates the specified value and lifts the result in to an `Attempt` if evaluation completes
   * without throwing. If a non-fatal exception is thrown, it is returned wrapped in `Left`. Fatal
   * exceptions are thrown.
   */
  def apply[A](a: => A): Attempt[A] =
    try Right(a) catch { case NonFatal(t) => Left(t) }

  /** Alias for `Right(a)` but returns the result as an `Attempt[A]` instead of a right. */
  def success[A](a: A): Attempt[A] = Right(a)

  /** Alias for `Left(t)` but returns the result as an `Attempt[Nothing]` instead of a left. */
  def failure(t: Throwable): Attempt[Nothing] = Left(t)
}
