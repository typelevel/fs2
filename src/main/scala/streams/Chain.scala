package streams

private[streams] sealed trait Chain[F[_],A,B] { self =>
  def uncons: Either[(A => B, B => A), (A => F[x], Chain[F,x,B]) forSome { type x }]
  def +:[A0](f: A0 => F[A]): Chain[F,A0,B] = new Chain[F,A0,B] {
    def uncons = Right((f,self))
  }
  def step(a: A): Option[(F[x], Chain[F,x,B]) forSome { type x }] = uncons match {
    case Left(_) => None
    case Right((h,t)) => Some(h(a) -> t)
    case _ => sys.error("Needed because of buggy Scala pattern coverage checker")
  }
}
object Chain {
  def empty[F[_],A]: Chain[F,A,A] = new Chain[F,A,A] {
    def uncons = Left((identity, identity))
  }
  def single[F[_],A,B](f: A => F[B]): Chain[F,A,B] =
    f +: empty
}
