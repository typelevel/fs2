package fs2

trait Functor[F[_]] {
  def map[A,B](a: F[A])(f: A => B): F[B]
}

