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
}

object Applicative {
  def apply[F[_]](implicit F: Applicative[F]): Applicative[F] = F

  /**
   * Returns an effect that when evaluated, first evaluates `fa` then `fb` and
   * combines the results with `f`.
   */
  // TODO: Move this to Applicative trait in 1.0
  private[fs2] def map2[F[_],A,B,C](fa: F[A], fb: F[B])(f: (A, B) => C)(implicit F: Applicative[F]): F[C] =
    F.ap(fb)(F.ap(fa)(F.pure(f.curried)))
}
