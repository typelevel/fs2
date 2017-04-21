package fs2

import cats.Monad

/**
 * Type classes and other utilities used by [[fs2]].
 */
package object util {

  /** Alias for `Either[Throwable,A]`. */
  type Attempt[+A] = Either[Throwable,A]

  private[fs2] def defaultTailRecM[F[_], A, B](a: A)(f: A => F[Either[A,B]])(implicit F: Monad[F]): F[B] =
    F.flatMap(f(a)) {
      case Left(a2) => defaultTailRecM(a2)(f)
      case Right(b) => F.pure(b)
    }
}
