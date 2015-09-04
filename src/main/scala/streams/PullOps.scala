package streams

private[streams] trait PullOps[+F[_],+W,+R] { self: Pull[F,W,R] =>

  def or[F2[x]>:F[x],W2>:W,R2>:R](p2: => Pull[F2,W2,R2])(
    implicit S1: RealSupertype[W,W2], R2: RealSupertype[R,R2])
    : Pull[F2,W2,R2]
    = Pull.or(self, p2)

  def map[R2](f: R => R2): Pull[F,W,R2] =
    Pull.map(self)(f)

  def flatMap[F2[x]>:F[x],W2>:W,R2](f: R => Pull[F2,W2,R2]): Pull[F2,W2,R2] =
    Pull.flatMap(self: Pull[F2,W2,R])(f)

  def filter(f: R => Boolean): Pull[F,W,R] = withFilter(f)

  def withFilter(f: R => Boolean): Pull[F,W,R] =
    self.flatMap(r => if (f(r)) Pull.pure(r) else Pull.done)
}
