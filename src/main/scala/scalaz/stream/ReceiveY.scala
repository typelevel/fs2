package scalaz.stream

import scalaz.{Applicative, Equal, Monad, Monoid}

trait ReceiveY[+A,+B] {
  import ReceiveY._

  def flip: ReceiveY[B,A] = this match {
    case ReceiveL(x) => ReceiveR(x)
    case ReceiveR(x) => ReceiveL(x)
    case Both_(x,y) => Both_(y,x)
  }

  def mapThis[A2](f: A => A2): ReceiveY[A2,B] = this match {
    case ReceiveL(a) => ReceiveL(f(a))
    case Both_(a,b) => Both_(f(a),b) 
    case t@ReceiveR(_) => t
  }

  def mapThat[B2](f: B => B2): ReceiveY[A,B2] = this match {
    case ReceiveR(b) => ReceiveR(f(b))
    case ReceiveY(a,b) => Both_(a, f(b))
    case t@ReceiveL(_) => t
  }

  def isThis: Boolean = this match {
    case ReceiveL(_) => true
    case _ => false
  }

  def isThat: Boolean = this match {
    case ReceiveR(_) => true
    case _ => false
  }

  def bitraverse[F[_],A2,B2](
      f: A => F[A2], g: B => F[B2])(
      implicit F: Applicative[F]): F[ReceiveY[A2,B2]] = {
    import F.applicativeSyntax._                      
    this match {
      case ReceiveL(a) => f(a) map (ReceiveL(_))
      case ReceiveR(b) => g(b) map (ReceiveR(_))
      case ReceiveY(a,b) => ^(f(a), g(b))(ReceiveY(_,_))
    }
  }
}

object ReceiveY {
  case class ReceiveL[+A](get: A) extends ReceiveY[A, Nothing]
  case class ReceiveR[+B](get: B) extends ReceiveY[Nothing, B]
  case class HaltL(rsn:Throwable) extends ReceiveY[Nothing, Nothing]
  case class HaltR(rsn:Throwable) extends ReceiveY[Nothing, Nothing]

  private[stream] case class Both_[+X,+Y](left: X, right: Y) extends ReceiveY[X, Y] {
    override def toString = s"These($left, $right)" 
  }

  def apply[X,Y](left: X, right: Y): ReceiveY[X,Y] = Both_(left, right)

  def unapply[X,Y](t: ReceiveY[X,Y]): Option[(X,Y)] =
    t match {
      case Both_(l,r) => Some((l, r)) 
      case _ => None
    }

  implicit def theseEqual[X, Y](implicit X: Equal[X], Y: Equal[Y]): Equal[ReceiveY[X, Y]] =
    Equal.equal{
      case (ReceiveL(a), ReceiveL(b)) => X.equal(a, b)
      case (ReceiveR(a), ReceiveR(b)) => Y.equal(a, b)
      case (a @ Both_(_, _), b @ Both_(_, _)) =>
        X.equal(a.left, b.left) && Y.equal(b.right, b.right)
      case _ => false
    }
  
  implicit def theseInstance[X](implicit X: Monoid[X]) = 
  new Monad[({type f[y] = ReceiveY[X,y]})#f] {
    def point[Y](x: => Y): ReceiveY[X,Y] = ReceiveR(x)
    def bind[Y,Y2](t: ReceiveY[X,Y])(f: Y => ReceiveY[X,Y2]): ReceiveY[X,Y2] =
      t match {
        case a@ReceiveL(_) => a
        case ReceiveR(x) => f(x)
        case Both_(x1, y1) => f(y1) match {
          case ReceiveL(x2) => ReceiveL(X.append(x1, x2))
          case ReceiveR(y2) => Both_(x1, y2)
          case Both_(x2, y2) => Both_(X.append(x1, x2), y2)
        }
      }
  }

  def align[A,B](a: Seq[A], b: Seq[B]): Stream[ReceiveY[A,B]] =
    if (a.isEmpty) b.view.map(ReceiveR(_)).toStream
    else if (b.isEmpty) a.view.map(ReceiveL(_)).toStream
    else Both_(a.head, b.head) #:: align(a.tail, b.tail)

  def unalign[A,B](s: Seq[ReceiveY[A,B]]): (Stream[A], Stream[B]) =
    (concatThis(s), concatThat(s))

  def concatThis[A,B](s: Seq[ReceiveY[A,B]]): Stream[A] =
    s.view.flatMap { case ReceiveL(a) => List(a); case Both_(a,b) => List(a); case _ => List() }.toStream

  def concatThat[A,B](s: Seq[ReceiveY[A,B]]): Stream[B] =
    s.view.flatMap { case ReceiveR(b) => List(b); case Both_(a,b) => List(b); case _ => List() }.toStream

  import scalaz.syntax.{ApplyOps, ApplicativeOps, FunctorOps, MonadOps}
  
  trait TheseT[X] { type f[y] = ReceiveY[X,y] }

  implicit def toMonadOps[X:Monoid,A](f: ReceiveY[X,A]): MonadOps[TheseT[X]#f,A] =
    theseInstance.monadSyntax.ToMonadOps(f)
  implicit def toApplicativeOps[X:Monoid,A](f: ReceiveY[X,A]): ApplicativeOps[TheseT[X]#f,A] =
    theseInstance.applicativeSyntax.ToApplicativeOps(f)
  implicit def toApplyOps[X:Monoid,A](f: ReceiveY[X,A]): ApplyOps[TheseT[X]#f,A] =
    theseInstance.applySyntax.ToApplyOps(f)
  implicit def toFunctorOps[X:Monoid,A](f: ReceiveY[X,A]): FunctorOps[TheseT[X]#f,A] =
    theseInstance.functorSyntax.ToFunctorOps(f)
}
