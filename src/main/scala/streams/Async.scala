package streams

trait Monad[F[_]] {
  def map[A,B](a: F[A])(f: A => B): F[B] = bind(a)(f andThen (pure))
  def bind[A,B](a: F[A])(f: A => F[B]): F[B]
  def pure[A](a: A): F[A]
}

trait Affine[F[_]] extends Monad[F] {
  /**
   * Satisfies `affine(f) flatMap { f => f flatMap { _ => f }} == f`.
   * In other words, we only pay for the effect of `f` once, regardless
   * of how many places we bind to it.
   */
  def affine[A](f: F[A]): F[F[A]]
}

trait Async[F[_]] extends Monad[F] with Affine[F] {
  type Pool[A]

  /** Create a asynchronous, concurrent pool. */
  def pool[A]: F[Pool[A]]

  /**
   * Add a task to a `Pool`. After the returned `F[Unit]` is bound, the
   * task is running in the background. Multiple tasks may be added to a
   * `Pool[A]`. Satisfies `put(pool)(t) flatMap { _ => take(pool) } == t`.
   */
  def put[A](q: Pool[A])(a: F[A]): F[Unit]
  def putFree[A](q: Pool[A])(a: Free[F,A]): F[Unit]

  /**
   * Obtain the first available result from the `Pool`.
   */
  def take[A](q: Pool[A]): F[A]

  /**
   * Chooses nondeterministically between `a map (Left(_))` and
   * `a2 map (Right(_))`. Result must be equivalent to one of
   * these two expressions. */
  def race[A,B](a: F[A], a2: F[B]): F[Either[A,B]]

  def affine[A](f: F[A]): F[F[A]] = bind(pool[A]) { pool =>
    map(put(pool)(f)) { _ => take(pool) }
  }
}
