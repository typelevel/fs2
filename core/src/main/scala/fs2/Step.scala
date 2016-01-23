package fs2

case class Step[+A,+B](head: A, tail: B)

object #: {
  def unapply[A,B](s: Step[A,B]) = Some((s.head, s.tail))
}
