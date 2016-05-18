package fs2.util

trait Effect[F[_]] extends Catchable[F] {
  def delay[A](a: => A): F[A]
  def suspend[A](fa: => F[A]): F[A]
}
