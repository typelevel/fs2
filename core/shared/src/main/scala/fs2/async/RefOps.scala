package fs2.async

/**
 * Operations on concurrent, mutable reference types.
 */
trait RefOps[F[_], A] {

  /**
   * Obtains the current value, or waits until it has been `set`.
   *
   * Note: some ref types always have a value, and hence, no waiting occurs when binding the returned action.
   */
  def get: F[A]

  /** *Asynchronously* sets the current value to the supplied pure value. */
  def setAsyncPure(a: A): F[Unit]

  /**
   * *Synchronously* sets the current value.
   *
   * The returned value completes evaluating after the reference has been successfully set.
   *
   * Satisfies: `r.setSync(fa) *> r.get == fa`
   */
  def setSync(fa: F[A]): F[Unit]

  /**
   * *Synchronously* sets the current value to the supplied pure value.
   *
   * Satisfies: `r.setSyncPure(a) *> r.get == pure(a)`
   */
  def setSyncPure(a: A): F[Unit]

  /**
   * Attempts to modify the current value once, returning `None` if another
   * concurrent modification completes between the time the variable is
   * read and the time it is set.
   */
  def tryModify(f: A => A): F[Option[Change[A]]]

  /** Like `tryModify` but allows returning a `B` along with the update. */
  def tryModify2[B](f: A => (A,B)): F[Option[(Change[A], B)]]

  /** Like `tryModify` but does not complete until the update has been successfully made. */
  def modify(f: A => A): F[Change[A]]

  /** Like `modify` but allows returning a `B` along with the update. */
  def modify2[B](f: A => (A,B)): F[(Change[A], B)]
}
