package fs2.util

/**
 * Applicative type class.
 *
 * For infix syntax, including derived methods like `product`, import `fs2.util.syntax._`.
 *
 * @see [[http://strictlypositive.org/IdiomLite.pdf]]
 */
trait Applicative[F[_]] extends Functor[F] {

  /** Lifts a pure value in to the `F` effect. */
  def pure[A](a: A): F[A]

  /** Converts an `F[A]` to an `F[B]` using an effectful function `F[A => B]`. */
  def ap[A,B](fa: F[A])(f: F[A => B]): F[B]
}
