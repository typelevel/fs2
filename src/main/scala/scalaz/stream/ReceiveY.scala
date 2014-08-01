package scalaz.stream

import scalaz.{Monoid, Applicative, Equal, Monad}


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

  implicit def receiveYequal[X, Y](implicit X: Equal[X], Y: Equal[Y]): Equal[ReceiveY[X, Y]] =
    Equal.equal{
      case (ReceiveL(a), ReceiveL(b)) => X.equal(a, b)
      case (ReceiveR(a), ReceiveR(b)) => Y.equal(a, b)
      case _ => false
    }

  implicit def receiveYInstance[X](implicit X: Monoid[X]) =
    new Monad[({type f[y] = ReceiveY[X,y]})#f] {
      def point[Y](x: => Y): ReceiveY[X,Y] = ReceiveR(x)
      def bind[Y,Y2](t: ReceiveY[X,Y])(f: Y => ReceiveY[X,Y2]): ReceiveY[X,Y2] =
        t match {
          case a@ReceiveL(_) => a
          case ReceiveR(x) => f(x)
          case h:HaltOne => h
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

  import scalaz.syntax.{ApplyOps, ApplicativeOps, FunctorOps, MonadOps}

  trait ReceiveT[X] { type f[y] = ReceiveY[X,y] }

  implicit def toMonadOps[X:Monoid,A](f: ReceiveY[X,A]): MonadOps[ReceiveT[X]#f,A] =
    receiveYInstance.monadSyntax.ToMonadOps(f)
  implicit def toApplicativeOps[X:Monoid,A](f: ReceiveY[X,A]): ApplicativeOps[ReceiveT[X]#f,A] =
    receiveYInstance.applicativeSyntax.ToApplicativeOps(f)
  implicit def toApplyOps[X:Monoid,A](f: ReceiveY[X,A]): ApplyOps[ReceiveT[X]#f,A] =
    receiveYInstance.applySyntax.ToApplyOps(f)
  implicit def toFunctorOps[X:Monoid,A](f: ReceiveY[X,A]): FunctorOps[ReceiveT[X]#f,A] =
    receiveYInstance.functorSyntax.ToFunctorOps(f)
}
