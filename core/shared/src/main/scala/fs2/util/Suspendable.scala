package fs2.util

/**
 * Monad which supports capturing a deferred evaluation of a by-name `F[A]`.
 *
 * Evaluation is suspended until a value is extracted, typically via the `unsafeRunAsync`
 * method on the related [[Effect]] type class or via a type constructor specific extraction
 * method (e.g., `unsafeRunSync` on `Task`). Side-effects that occur while evaluating a
 * suspension are evaluated exactly once at the time of extraction.
 */
trait Suspendable[F[_]] extends Monad[F] {

  /**
   * Returns an `F[A]` that evaluates and runs the provided `fa` on each run.
   */
  def suspend[A](fa: => F[A]): F[A]

  /**
   * Promotes a non-strict value to an `F`, catching exceptions in the process.
   * Evaluates `a` each time the returned effect is run.
   */
  def delay[A](a: => A): F[A] = suspend(pure(a))
}

