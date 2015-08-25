package streams

/** A `Sub[F,G]` is evidence that `forall x . F[x] <: G[x]` */
sealed trait Sub1[-F[_],+G[_]] {
  def apply[A](f: F[A]): G[A] =
    Sub1.subst[({ type ap[h[_],x] = h[x] })#ap, F, G, A](f)(this)
}

private[streams] trait Sub1Instances {
  implicit def sub1[F[_]]: Sub1[F,F] = new Sub1[F,F] {}
}

object Sub1 extends Sub1Instances {
  implicit def nothingIsSub1: Sub1[Nothing,Nothing] = new Sub1[Nothing,Nothing] {}

  /** Safely coerce an `H[F,x]` to an `H[G,x]`. */
  def subst[H[_[_],_], F[_], G[_], x](hf: H[F,x])(implicit S: Sub1[F,G]): H[G,x] =
    hf.asInstanceOf[H[G,x]]

  def substStream[F[_],G[_],A](p: Stream[F,A])(implicit S: Sub1[F,G]): Stream[G,A] =
    subst[Stream,F,G,A](p)

  def substStreamF[F[_],G[_],A,B](g: A => Stream[F,B])(implicit S: Sub1[F,G]): A => Stream[G,B] =
    subst[({ type f[g[_],x] = A => Stream[g,x] })#f, F, G, B](g)

  def substKleisli[F[_],G[_],A,B](g: A => F[B])(implicit S: Sub1[F,G]): A => G[B] =
    subst[({ type f[g[_],x] = A => g[x] })#f, F, G, B](g)

  def substFree[F[_],G[_],A](p: Free[F,A])(implicit S: Sub1[F,G]): Free[G,A] =
    subst[Free,F,G,A](p)

  def substPull[F[_],G[_],W,R](p: Pull[F,W,R])(implicit S: Sub1[F,G]): Pull[G,W,R] =
    subst[({ type f[g[_],x] = Pull[g,W,x] })#f,F,G,R](p)
}

