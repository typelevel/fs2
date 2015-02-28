package neutral.stream

sealed trait ReceiveY[+A,+B] {
  import ReceiveY._

  def flip: ReceiveY[B,A] = this match {
    case ReceiveL(x) => ReceiveR(x)
    case ReceiveR(x) => ReceiveL(x)
    case HaltL(e) => HaltR(e)
    case HaltR(e) => HaltL(e)
  }

  def mapL[A2](f: A => A2): ReceiveY[A2,B] = this match {
    case ReceiveL(a) => ReceiveL(f(a))
    case t@ReceiveR(_) => t
    case h:HaltOne => h
  }

  def mapR[B2](f: B => B2): ReceiveY[A,B2] = this match {
    case ReceiveR(b) => ReceiveR(f(b))
    case t@ReceiveL(_) => t
    case h:HaltOne => h
  }

  def isL: Boolean = this match {
    case ReceiveL(_) => true
    case _ => false
  }

  def isR: Boolean = this match {
    case ReceiveR(_) => true
    case _ => false
  }

  def isHalted = haltedBy.isDefined

  def haltedBy: Option[Cause] = this match {
    case h:HaltOne => Some(h.cause)
    case _ => None
  }


}

object ReceiveY {
  case class ReceiveL[+A](get: A) extends ReceiveY[A, Nothing]
  case class ReceiveR[+B](get: B) extends ReceiveY[Nothing, B]
  sealed trait HaltOne extends ReceiveY[Nothing, Nothing] {
    val cause: Cause
  }
  case class HaltL(cause:Cause) extends HaltOne
  case class HaltR(cause:Cause) extends HaltOne
  object HaltOne {
    def unapply(ry:ReceiveY[Any,Any]) : Option[Cause] = {
      ry match {
        case h:HaltOne => Some(h.cause)
        case _ => None
      }
    }
  }


  def align[A,B](a: Seq[A], b: Seq[B]): Stream[ReceiveY[A,B]] =
    if (a.isEmpty) b.view.map(ReceiveR(_)).toStream
    else a.view.map(ReceiveL(_)).toStream

  def unalign[A,B](s: Seq[ReceiveY[A,B]]): (Stream[A], Stream[B]) =
    (concatLeft(s), concatRight(s))

  def concatLeft[A,B](s: Seq[ReceiveY[A,B]]): Stream[A] =
    s.view.flatMap { case ReceiveL(a) => List(a); case _ => List() }.toStream

  def concatRight[A,B](s: Seq[ReceiveY[A,B]]): Stream[B] =
    s.view.flatMap { case ReceiveR(b) => List(b); case _ => List() }.toStream

  trait ReceiveT[X] { type f[y] = ReceiveY[X,y] }

}
