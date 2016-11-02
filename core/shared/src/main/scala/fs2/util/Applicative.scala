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

  /**
   * Converts an `F[A]` to an `F[B]` using an effectful function `F[A => B]`.
   * The function effect is evaluated first, followed by the `F[A]` effect.
   */
  def ap[A,B](fa: F[A])(f: F[A => B]): F[B]

  /**
   * Returns an effect that when evaluated, first evaluates `fa` then `fb` and
   * combines the results with `f`.
   */
  def map2[A,B,C](fa: F[A], fb: F[B])(f: (A, B) => C): F[C] =
    ap(fb)(ap(fa)(pure(f.curried)))
}

object Applicative {
  def apply[F[_]](implicit F: Applicative[F]): Applicative[F] = F
}
