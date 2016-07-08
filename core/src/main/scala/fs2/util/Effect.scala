package fs2.util

trait Effect[F[_]] extends Catchable[F] {

  /**
   * Returns an `F[A]` that evaluates and runs the provided `fa` on each run.
   */
  def suspend[A](fa: => F[A]): F[A]

  /**
   * Promotes a non-strict value to an `F`, catching exceptions in the process.
   * Evaluates `a` each time the returned effect is run.
   */
  def delay[A](a: => A): F[A] = suspend(pure(a))

  /**
   * Evaluates the specified `F[A]`, possibly asynchronously, and calls the specified
   * callback with the result of the evaluation.
   */
  def unsafeRunAsync[A](fa: F[A])(cb: Either[Throwable, A] => Unit): Unit
}
