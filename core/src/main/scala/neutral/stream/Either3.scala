package neutral.stream

protected[stream] sealed abstract class Either3[+A, +B, +C] extends Product with Serializable {
  def fold[Z](left: A => Z, middle: B => Z, right: C => Z): Z = this match {
    case Left3(a)   => left(a)
    case Middle3(b) => middle(b)
    case Right3(c)  => right(c)
  }

  def eitherLeft: (A \/ B) \/ C = this match {
    case Left3(a)   => -\/(-\/(a))
    case Middle3(b) => -\/(\/-(b))
    case Right3(c)  => \/-(c)
  }

  def eitherRight: A \/ (B \/ C) = this match {
    case Left3(a)   => -\/(a)
    case Middle3(b) => \/-(-\/(b))
    case Right3(c)  => \/-(\/-(c))
  }

  def leftOr[Z](z: => Z)(f: A => Z)   = fold(f, _ => z, _ => z)
  def middleOr[Z](z: => Z)(f: B => Z) = fold(_ => z, f, _ => z)
  def rightOr[Z](z: => Z)(f: C => Z)  = fold(_ => z, _ => z, f)
}

protected[stream] final case class Left3[+A, +B, +C](a: A) extends Either3[A, B, C]
protected[stream] final case class Middle3[+A, +B, +C](b: B) extends Either3[A, B, C]
protected[stream] final case class Right3[+A, +B, +C](c: C) extends Either3[A, B, C]

protected[stream] object Either3 {
  def left3[A, B, C](a: A):   Either3[A, B, C] = Left3(a)
  def middle3[A, B, C](b: B): Either3[A, B, C] = Middle3(b)
  def right3[A, B, C](c: C):  Either3[A, B, C] = Right3(c)
}
