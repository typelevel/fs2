package fs2.util

/** Monad which supports catching exceptions, suspending evaluation, and potentially asynchronous evaluation. */
trait Effect[F[_]] extends Catchable[F] with Suspendable[F] {

  /**
   * Evaluates the specified `F[A]`, possibly asynchronously, and calls the specified
   * callback with the result of the evaluation.
   */
  def unsafeRunAsync[A](fa: F[A])(cb: Attempt[A] => Unit): Unit
}
