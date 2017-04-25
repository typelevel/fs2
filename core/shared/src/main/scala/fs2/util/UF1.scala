package fs2.util

/** A `forall a . f a -> g a`. */
trait UF1[-F[_],+G[_]] { self =>
  def apply[A](f: F[A]): G[A]
  def andThen[H[_]](g: UF1[G, H]): UF1[F,H] = new UF1[F,H] {
    def apply[A](f: F[A]): H[A] = g(self(f))
  }
}

object UF1 {
  def id[F[_]]: UF1[F, F] = new UF1[F, F] { def apply[A](f: F[A]) = f }
}
