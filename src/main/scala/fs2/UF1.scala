package fs2

/** A `forall a . f a -> g a`. */
trait UF1[-F[_],+G[_]] { def apply[A](f: F[A]): G[A] }

object UF1 {
  type ~>[-F[_],+G[_]] = UF1[F,G]

  def id[F[_]]: (F ~> F) = new UF1[F,F] { def apply[A](f: F[A]) = f }
}
