package fs2.fast.internal

import fs2.util._
import Free.ViewL
import Free.ViewL._
import Free._

import cats.MonadError
import cats.implicits._

sealed abstract class Free[F[_], +R] {

  def flatMap[R2](f: R => Free[F, R2]): Free[F, R2] = Bind(this, f)
  def map[R2](f: R => R2): Free[F,R2] = Bind(this, (r: R) => Free.Pure(f(r)))
  def onError[R2>:R](h: Throwable => Free[F,R2]): Free[F,R2] = OnError(this, h)
  lazy val viewL: ViewL[F,R] = ViewL(this) // todo - review this
  def translate[G[_]](f: UF1[F, G]): Free[G, R] = this.viewL match {
    case Done(r) => Pure(r)
    case b: Bound[F,_,R] =>
      b.onError match {
        case None => Eval(f(b.fx)) flatMap (x => b.tryBind(x).translate(f))
        case Some(h) =>
          OnError(Eval(f(b.fx)) flatMap (x => b.tryBind(x).translate(f)),
                  err => h(err).translate(f))
      }
    case Failed(err) => Fail(err)
  }
}

object Free {
  case class Pure[F[_], R](r: R) extends Free[F, R] {
    override def translate[G[_]](f: UF1[F, G]): Free[G, R] = this.asInstanceOf[Free[G,R]]
  }
  case class Eval[F[_], R](fr: F[R]) extends Free[F, R] {
    override def translate[G[_]](f: UF1[F, G]): Free[G, R] = Eval(f(fr))
  }
  case class Bind[F[_], X, R](fx: Free[F, X], f: X => Free[F, R]) extends Free[F, R]
  case class OnError[F[_],R](fr: Free[F,R], onError: Throwable => Free[F,R]) extends Free[F,R]
  case class Fail[F[_], R](error: Throwable) extends Free[F,R] {
    override def translate[G[_]](f: UF1[F, G]): Free[G, R] = this.asInstanceOf[Free[G,R]]
  }

  def Try[F[_],R](f: => Free[F,R]): Free[F,R] =
    try f catch { case NonFatal(t) => Fail(t) }

  sealed abstract class ViewL[F[_], +R]

  object ViewL {
    class Bound[F[_], X, R] private (val fx: F[X], f: X => Free[F, R], val onError: Option[Throwable => Free[F,R]]) extends ViewL[F, R] {
      def handleError(e: Throwable): Free[F,R] = onError match {
        case None => Fail[F,R](e)
        case Some(h) => Try(h(e))
      }
      def tryBind: X => Free[F,R] = x =>
        try propagateErrorHandler(f(x))
        catch { case e: Throwable => handleError(e) }
      def propagateErrorHandler(fr: Free[F,R]): Free[F,R] =
        onError match {
          case None => fr
          case Some(h) => fr.onError(h)
        }
    }
    object Bound {
      def apply[F[_],X,R](fx: F[X], f: X => Free[F,R], onError: Option[Throwable => Free[F,R]]): Bound[F,X,R] =
        new Bound(fx, f, onError)
    }
    case class Done[F[_], R](r: R) extends ViewL[F,R]
    case class Failed[F[_],R](error: Throwable) extends ViewL[F,R]

    def apply[F[_], R](free: Free[F, R]): ViewL[F, R] = {
      type FreeF[x] = Free[F,x]
      type X = Any
      @annotation.tailrec
      def go(free: Free[F, X], k: Option[X => Free[F,R]],
                               onErr: Option[Throwable => Free[F,R]]): ViewL[F, R] = free match {
        case Pure(x) => k match {
          case None => ViewL.Done(x.asInstanceOf[R])
          case Some(f) => go(Try(f(x)).asInstanceOf[Free[F,X]], None, None)
        }
        case Eval(fx) => k match {
          case None => ViewL.Bound(fx, (x: X) => Free.Pure(x.asInstanceOf[R]), onErr)
          case Some(f) => ViewL.Bound(fx, f, onErr)
        }
        case Fail(err) => onErr match {
          case None => ViewL.Failed(err)
          case Some(onErr) => go(Try(onErr(err)).asInstanceOf[Free[F,X]], None, None)
        }
        case OnError(fx, onErrInner) => k match {
          case None => onErr match {
            case None => go(fx, None, Some((e: Throwable) => Try(onErrInner(e)).asInstanceOf[Free[F,R]]))
            case Some(onErr) => go(fx, None,
              Some((e: Throwable) => OnError(Try(onErrInner(e)).asInstanceOf[Free[F,R]], onErr)))
          }
          case Some(k2) => onErr match {
            case None => go(fx, k, Some((e: Throwable) => Try(onErrInner(e)) flatMap k2))
            case Some(onErr) => go(fx, k,
              Some((e: Throwable) => OnError(Try(onErrInner(e)) flatMap k2, onErr)))
          }
        }
        case b: Free.Bind[F, _, X] =>
          val fw: Free[F, Any] = b.fx.asInstanceOf[Free[F, Any]]
          val f: Any => Free[F, X] = b.f.asInstanceOf[Any => Free[F, X]]
          k match {
            case None => go(fw, Some(f.asInstanceOf[X => Free[F,R]]), onErr)
            case Some(g) => go(fw, Some(w => f(w).flatMap(g)), onErr)
          }
      }
      go(free.asInstanceOf[Free[F,X]], None, None)
    }
  }

  implicit class FreeRunOps[F[_],R](val self: Free[F,R]) extends AnyVal {
    def run(implicit F: MonadError[F, Throwable]): F[R] = {
      self.viewL match {
        case Done(r) => F.pure(r)
        case Failed(t) => F.raiseError(t)
        case b: Bound[F,_,R] =>
          b.onError match {
            case None => F.flatMap(b.fx)(x => b.tryBind(x).run)
            case Some(h) => F.flatMap(b.fx)(x => b.tryBind(x).run).handleErrorWith(t => h(t).run)
          }
      }
  }
  }
}
