package fs2.util

/**
 * Monad type class.
 *
 * For infix syntax, including derived methods like `flatten`, import `fs2.util.syntax._`.
 */
trait Monad[F[_]] extends Applicative[F] {
  /** Computes an `F[B]` from `A` value(s) in an `F[A]`. */
  def flatMap[A,B](a: F[A])(f: A => F[B]): F[B]

  def map[A,B](a: F[A])(f: A => B): F[B] = flatMap(a)(f andThen pure)
  def ap[A,B](fa: F[A])(f: F[A => B]): F[B] = flatMap(fa)(a => map(f)(_(a)))
}

object Monad {
  def apply[F[_]](implicit F: Monad[F]): Monad[F] = F
}
