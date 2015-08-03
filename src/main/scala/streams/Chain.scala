package streams

private[streams] sealed trait Chain[F[_,_],A,B] { self =>
  def apply[R](empty: (A => B, B => A) => R, cons: H[R]): R
  trait H[+R] { def f[x]: (F[A,x], Chain[F,x,B]) => R }
  def push[A0](f: F[A0,A]): Chain[F,A0,B] = f +: self
  def +:[A0](f: F[A0,A]): Chain[F,A0,B] = Chain.Cons(f, self)
}

private[streams] object Chain {
  case class Empty[F[_,_],A]() extends Chain[F,A,A] {
    def apply[R](empty: (A => A, A => A) => R, cons: H[R]): R =
      empty(identity, identity)
  }
  case class Cons[F[_,_],A,x,B](head: F[A,x], tail: Chain[F,x,B]) extends Chain[F,A,B] {
    def apply[R](empty: (A => B, B => A) => R, cons: H[R]): R =
      cons.f(head, tail)
  }

  def empty[F[_,_],A]: Chain[F,A,A] = Empty()
  def single[F[_,_],A,B](f: F[A,B]): Chain[F,A,B] = Cons(f, empty)
}
