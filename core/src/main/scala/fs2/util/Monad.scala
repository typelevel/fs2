package fs2.util

trait Monad[F[_]] extends Functor[F] {
  def pure[A](a: A): F[A]
  def flatMap[A,B](a: F[A])(f: A => F[B]): F[B]

  def map[A,B](a: F[A])(f: A => B): F[B] = flatMap(a)(f andThen (pure))

  def traverse[A,B](v: Seq[A])(f: A => F[B]): F[Vector[B]] =
    v.reverse.foldLeft(pure(Vector.empty[B])) {
      (tl,hd) => flatMap(f(hd)) { b => map(tl)(b +: _) }
    }

  def sequence[A](v: Seq[F[A]]): F[Vector[A]] =
    traverse(v)(identity)
}
