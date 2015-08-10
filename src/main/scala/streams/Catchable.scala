package streams

trait Catchable[F[_]] extends Monad[F] {
  def fail[A](err: Throwable): F[A]
  def attempt[A](fa: F[A]): F[Either[Throwable,A]]
}
