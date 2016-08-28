package fs2.util

/**
 * Functor type class.
 *
 * For infix syntax, including derived methods like `as`, import `fs2.util.syntax._`.
 */
trait Functor[F[_]] {
  /** Converts an `F[A]` to an `F[B]` using a pure function `A => B`. */
  def map[A,B](a: F[A])(f: A => B): F[B]
}
