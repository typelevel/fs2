package fs2
package util

/** A `Sub[F,G]` is evidence that `forall x . F[x] <: G[x]` */
sealed trait Sub1[-F[_],+G[_]] { self =>
  def apply[A](f: F[A]): G[A] =
    Sub1.subst[({ type ap[h[_],x] = h[x] })#ap, F, G, A](f)(this)
}

private[fs2] trait Sub1Instances1 {
  /** Okay to promote a `Pure` to any other `F`. */
  implicit def pureIsSub1[F[_]]: Sub1[Pure,F] = new Sub1[Pure,F] {}
}

private[fs2] trait Sub1Instances0 extends Sub1Instances1 {
  /** Coax to `Pure` if `Nothing`. */
  implicit def pureIsSub1Refl: Sub1[Pure,Pure] = new Sub1[Pure,Pure] {}
}

object Sub1 extends Sub1Instances0 {
  /** Prefer to match exactly. */
  implicit def sub1[F[_]]: Sub1[F,F] = new Sub1[F,F] {}

  /** Safely coerce an `H[F,x]` to an `H[G,x]`. */
  def subst[H[_[_],_], F[_], G[_], x](hf: H[F,x])(implicit S: Sub1[F,G]): H[G,x] =
    hf.asInstanceOf[H[G,x]]

  def substStream[F[_],G[_],A](p: Stream[F,A])(implicit S: Sub1[F,G]): Stream[G,A] =
    subst[Stream,F,G,A](p)

  def substStreamCore[F[_],G[_],A](p: StreamCore[F,A])(implicit S: Sub1[F,G])
  : StreamCore[G,A] = subst[StreamCore,F,G,A](p)

  def substSegment[F[_],G[_],A](p: StreamCore.Segment[F,A])(implicit S: Sub1[F,G]): StreamCore.Segment[G,A] =
    subst[StreamCore.Segment,F,G,A](p)

  import Stream.Handle
  def substHandle[F[_],G[_],A](p: Handle[F,A])(implicit S: Sub1[F,G]): Handle[G,A] =
    subst[Handle,F,G,A](p)

  def substStreamF[F[_],G[_],A,B](g: A => Stream[F,B])(implicit S: Sub1[F,G]): A => Stream[G,B] =
    subst[({ type f[g[_],x] = A => Stream[g,x] })#f, F, G, B](g)

  def substStreamCoreF[F[_],G[_],A,B](g: A => StreamCore[F,B])(implicit S: Sub1[F,G]): A => StreamCore[G,B] =
    subst[({ type f[g[_],x] = A => StreamCore[g,x] })#f, F, G, B](g)

  def substKleisli[F[_],G[_],A,B](g: A => F[B])(implicit S: Sub1[F,G]): A => G[B] =
    subst[({ type f[g[_],x] = A => g[x] })#f, F, G, B](g)

  def substFree[F[_],G[_],A](p: Free[F,A])(implicit S: Sub1[F,G]): Free[G,A] =
    subst[Free,F,G,A](p)

  def substPull[F[_],G[_],W,R](p: Pull[F,W,R])(implicit S: Sub1[F,G]): Pull[G,W,R] =
    subst[({ type f[g[_],x] = Pull[g,W,x] })#f,F,G,R](p)

  def substUF1[F[_],G[_],H[_]](u: F ~> G)(implicit S: Sub1[G,H]): F ~> H =
    subst[({ type f[g[_],x] = F ~> g })#f,G,H,Nothing](u)
}

