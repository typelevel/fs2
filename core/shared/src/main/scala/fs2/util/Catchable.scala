package fs2
package util

/**
 * Monad which tracks exceptions thrown during evaluation.
 *
 * For infix syntax, import `fs2.util.syntax._`.
 */
trait Catchable[F[_]] extends Monad[F] {
  /** Lifts a pure exception in to the error mode of the `F` effect. */
  def fail[A](err: Throwable): F[A]
  /** Provides access to the error in `fa`, if present, by wrapping it in a `Left`. */
  def attempt[A](fa: F[A]): F[Attempt[A]]
}

object Catchable {
  def apply[F[_]](implicit F: Catchable[F]): Catchable[F] = F

  implicit val attemptInstance: Catchable[Attempt] = new Catchable[Attempt] {
    def pure[A](a: A): Attempt[A] = Right(a)
    def flatMap[A, B](a: Attempt[A])(f: A => Attempt[B]): Attempt[B] = a.flatMap(f)
    def attempt[A](a: Attempt[A]): Attempt[Attempt[A]] = a match {
      case Right(a) => Right(Right(a))
      case Left(t) => Right(Left(t))
    }
    def fail[A](t: Throwable): Attempt[A] = Left(t)
    override def toString = "Catchable[Attempt]"
  }
}
