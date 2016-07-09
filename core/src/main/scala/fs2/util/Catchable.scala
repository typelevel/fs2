package fs2.util

/** Monad which tracks exceptions thrown during evaluation. */
trait Catchable[F[_]] extends Monad[F] {
  def fail[A](err: Throwable): F[A]
  def attempt[A](fa: F[A]): F[Attempt[A]]
}

object Catchable {

  implicit val attemptInstance: Catchable[Attempt] = new Catchable[Attempt] {
    def pure[A](a: A): Attempt[A] = Right(a)
    def flatMap[A, B](a: Attempt[A])(f: A => Attempt[B]): Attempt[B] = a.right.flatMap(f)
    def attempt[A](a: Attempt[A]): Attempt[Attempt[A]] = a match {
      case Right(a) => Right(Right(a))
      case Left(t) => Right(Left(t))
    }
    def fail[A](t: Throwable): Attempt[A] = Left(t)
    override def toString = "Catchable[Attempt]"
  }
}
