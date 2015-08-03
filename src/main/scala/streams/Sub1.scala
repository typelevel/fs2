package streams

/** A `Sub[F,G]` is evidence that `forall x . F[x] <: G[x]` */
sealed trait Sub1[-F[_],+G[_]] {
  def apply[A](f: F[A]): G[A] =
    Sub1.subst[({ type ap[h[_],x] = h[x] })#ap, F, G, A](f)(this)
}
object Sub1 {
  /** The sole `Sub1` instance. */
  implicit def sub1[F[_]]: Sub1[F,F] = new Sub1[F,F] {}

  /** Safely coerce an `H[F,x]` to an `H[G,x]`. */
  def subst[H[_[_],_], F[_], G[_], x](hf: H[F,x])(implicit S: Sub1[F,G]): H[G,x] =
    hf.asInstanceOf[H[G,x]]

  def substStream[F[_],G[_],A](p: Stream[F,A])(implicit S: Sub1[F,G]): Stream[G,A] =
    subst[Stream,F,G,A](p)
}

