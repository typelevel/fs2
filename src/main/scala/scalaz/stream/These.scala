package scalaz.stream

import scalaz.{Monad, Monoid, Equal}

trait These[+A,+B] {
  import These._

  def flip: These[B,A] = this match {
    case This(x) => That(x)
    case That(x) => This(x)
    case Both(x,y) => Both(y,x)
  }

  def mapThis[A2](f: A => A2): These[A2,B] = this match {
    case This(a) => This(f(a)) 
    case Both(a,b) => Both(f(a),b) 
    case t@That(_) => t
  }

  def mapThat[B2](f: B => B2): These[A,B2] = this match {
    case That(b) => That(f(b)) 
    case Both(a,b) => Both(a, f(b)) 
    case t@This(_) => t
  }

  def isThis: Boolean = this match {
    case This(_) => true
    case _ => false
  }

  def isThat: Boolean = this match {
    case That(_) => true
    case _ => false
  }
}

object These {
  case class This[+X](left: X) extends These[X, Nothing]
  case class That[+Y](right: Y) extends These[Nothing, Y]
  case class Both[+X,+Y](left: X, right: Y) extends These[X, Y]

  implicit def theseEqual[X, Y](implicit X: Equal[X], Y: Equal[Y]): Equal[These[X, Y]] =
    Equal.equal{
      case (This(a), This(b)) => X.equal(a, b)
      case (That(a), That(b)) => Y.equal(a, b)
      case (a @ Both(_, _), b @ Both(_, _)) =>
        X.equal(a.left, b.left) && Y.equal(b.right, b.right)
      case _ => false
    }
  
  implicit def theseInstance[X](implicit X: Monoid[X]) = 
  new Monad[({type f[y] = These[X,y]})#f] {
    def point[Y](x: => Y): These[X,Y] = That(x)
    def bind[Y,Y2](t: These[X,Y])(f: Y => These[X,Y2]): These[X,Y2] = 
      t match {
        case a@This(_) => a
        case That(x) => f(x)
        case Both(x1, y1) => f(y1) match {
          case This(x2) => This(X.append(x1, x2))
          case That(y2) => Both(x1, y2)
          case Both(x2, y2) => Both(X.append(x1, x2), y2)
        }
      }
  }

  def align[A,B](a: Seq[A], b: Seq[B]): Stream[These[A,B]] =
    if (a.isEmpty) b.view.map(That(_)).toStream
    else if (b.isEmpty) a.view.map(This(_)).toStream
    else Both(a.head, b.head) #:: align(a.tail, b.tail)

  def unalign[A,B](s: Seq[These[A,B]]): (Stream[A], Stream[B]) = 
    (concatThis(s), concatThat(s))

  def concatThis[A,B](s: Seq[These[A,B]]): Stream[A] = 
    s.view.flatMap { case This(a) => List(a); case Both(a,b) => List(a); case _ => List() }.toStream

  def concatThat[A,B](s: Seq[These[A,B]]): Stream[B] = 
    s.view.flatMap { case That(b) => List(b); case Both(a,b) => List(b); case _ => List() }.toStream

  import scalaz.syntax.{ApplyOps, ApplicativeOps, FunctorOps, MonadOps}
  
  trait TheseT[X] { type f[y] = These[X,y] }

  implicit def toMonadOps[X:Monoid,A](f: These[X,A]): MonadOps[TheseT[X]#f,A] = 
    theseInstance.monadSyntax.ToMonadOps(f)
  implicit def toApplicativeOps[X:Monoid,A](f: These[X,A]): ApplicativeOps[TheseT[X]#f,A] = 
    theseInstance.applicativeSyntax.ToApplicativeOps(f)
  implicit def toApplyOps[X:Monoid,A](f: These[X,A]): ApplyOps[TheseT[X]#f,A] = 
    theseInstance.applySyntax.ToApplyOps(f)
  implicit def toFunctorOps[X:Monoid,A](f: These[X,A]): FunctorOps[TheseT[X]#f,A] =
    theseInstance.functorSyntax.ToFunctorOps(f)
}
