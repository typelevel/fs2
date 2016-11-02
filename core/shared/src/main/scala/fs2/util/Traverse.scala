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
      v.reverse.foldLeft(G.pure(Vector.empty[B])) {
        (tl,hd) => G.map2(f(hd), tl)(_ +: _)
      }
    }
  }

  implicit val listInstance: Traverse[List] = new Traverse[List] {
    def map[A,B](l: List[A])(f: A => B): List[B] = l map f
    def traverse[G[_], A, B](l: List[A])(f: A => G[B])(implicit G: Applicative[G]): G[List[B]] = {
      l.reverse.foldLeft(G.pure(List.empty[B])) {
        (tl,hd) => G.map2(f(hd), tl)(_ :: _)
      }
    }
  }
}
