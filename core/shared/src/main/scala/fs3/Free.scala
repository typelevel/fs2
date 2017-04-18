package fs2.fast

import fs2.util._
import Free.ViewL

sealed abstract class Free[F[_], R] {
  def flatMap[R2](f: R => Free[F, R2]): Free[F, R2] = Free.Bind(this, f)
  def viewL: ViewL[F,R] = ViewL(this)
  def translate[G[_]](f: F ~> G): Free[G, R]
}

object Free {
  import ViewL._
  case class Pure[F[_], R](r: R) extends Free[F, R] {
    def translate[G[_]](f: F ~> G): Free[G, R] = this.asInstanceOf[Free[G,R]]
  }
  case class Eval[F[_], R](fr: F[R]) extends Free[F, R] {
    def translate[G[_]](f: F ~> G): Free[G, R] = Eval(f(fr))
  }
  case class Bind[F[_], X, R](fx: Free[F, X], f: X => Free[F, R]) extends Free[F, R] {
    def translate[G[_]](f: F ~> G): Free[G, R] = this.viewL match {
      case Done(r) => Pure(r)
      case Bound(fx, k) => Eval(f(fx)) flatMap (x => k(x).translate(f))
    }
  }

  sealed abstract class ViewL[F[_], R]

  object ViewL {
    case class Bound[F[_], X, R](fx: F[X], f: X => Free[F, R]) extends ViewL[F, R]
    case class Done[F[_], R](r: R) extends ViewL[F, R]

    def apply[F[_], R](free: Free[F, R]): ViewL[F, R] = {
      type FreeF[x] = Free[F,x]
      type X = Any
      @annotation.tailrec
      def go(free: Free[F, X], k: Option[X => Free[F,R]]): ViewL[F, R] = free match {
        case Free.Pure(x) => k match {
          case None => ViewL.Done(x.asInstanceOf[R])
          // todo - catch exceptions here
          case Some(f) => go(f(x).asInstanceOf[Free[F,X]], None)
        }
        case Free.Eval(fx) => k match {
          case None => ViewL.Bound(fx, (x: X) => Free.Pure(x.asInstanceOf[R]))
          case Some(f) => ViewL.Bound(fx, f)
        }
        case b: Free.Bind[F, _, X] =>
          val fw: Free[F, Any] = b.fx.asInstanceOf[Free[F, Any]]
          val f: Any => Free[F, X] = b.f.asInstanceOf[Any => Free[F, X]]
          k match {
            case None => go(fw, Some(f.asInstanceOf[X => Free[F,R]]))
            case Some(g) => go(fw, Some(w => f(w).flatMap(g)))
          }
      }
      go(free.asInstanceOf[Free[F,X]], None)
    }
  }
}

