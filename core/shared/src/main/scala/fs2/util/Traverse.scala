package fs2.util

/**
 * Traverse type class.
 *
 * For infix syntax, import `fs2.util.syntax._`.
 *
 * For parallel traverse and sequence, see the [[Async]] type class.
 *
 * @see [[http://strictlypositive.org/IdiomLite.pdf]]
 */
trait Traverse[F[_]] extends Functor[F] {

  def traverse[G[_]: Applicative, A, B](fa: F[A])(f: A => G[B]): G[F[B]]

  def sequence[G[_]: Applicative, A](fa: F[G[A]]): G[F[A]] =
    traverse(fa)(identity)
}

object Traverse {
  def apply[F[_]](implicit F: Traverse[F]): Traverse[F] = F

  implicit val vectorInstance: Traverse[Vector] = new Traverse[Vector] {
    def map[A,B](v: Vector[A])(f: A => B): Vector[B] = v map f
    def traverse[G[_], A, B](v: Vector[A])(f: A => G[B])(implicit G: Applicative[G]): G[Vector[B]] = {
      v.foldRight(G.pure(Vector.empty[B])) {
        (hd, init) => Applicative.map2(f(hd), init)(_ +: _)
      }
    }
  }

  implicit val listInstance: Traverse[List] = new Traverse[List] {
    def map[A,B](l: List[A])(f: A => B): List[B] = l map f
    def traverse[G[_], A, B](l: List[A])(f: A => G[B])(implicit G: Applicative[G]): G[List[B]] = {
      l.foldRight(G.pure(List.empty[B])) {
        (hd, init) => Applicative.map2(f(hd), init)(_ :: _)
      }
    }
  }

  implicit val seqInstance: Traverse[Seq] = new Traverse[Seq] {
    def map[A,B](l: Seq[A])(f: A => B): Seq[B] = l map f
    def traverse[G[_], A, B](l: Seq[A])(f: A => G[B])(implicit G: Applicative[G]): G[Seq[B]] = {
      l.foldRight(G.pure(Seq.empty[B])) {
        (hd, init) => Applicative.map2(f(hd), init)(_ +: _)
      }
    }
  }

}
