package fs2.internal

import Free.ViewL
import Free.ViewL._
import Free._

import cats.{ ~>, MonadError }
import cats.implicits._

sealed abstract class Free[F[_], +R] {

  def flatMap[R2](f: R => Free[F, R2]): Free[F, R2] = Bind(this, f)
  def map[R2](f: R => R2): Free[F,R2] = Bind(this, (r: R) => Free.Pure(f(r)))
  def onError[R2>:R](h: Throwable => Free[F,R2]): Free[F,R2] = OnError(this, h)
  lazy val viewL: ViewL[F,R] = ViewL(this) // todo - review this
  def translate[G[_]](f: F ~> G): Free[G, R] = this.viewL match {
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
    override def translate[G[_]](f: F ~> G): Free[G, R] = this.asInstanceOf[Free[G,R]]
  }
  case class Eval[F[_], R](fr: F[R]) extends Free[F, R] {
    override def translate[G[_]](f: F ~> G): Free[G, R] = Eval(f(fr))
  }
  case class Bind[F[_], X, R](fx: Free[F, X], f: X => Free[F, R]) extends Free[F, R]
  case class OnError[F[_],R](fr: Free[F,R], onError: Throwable => Free[F,R]) extends Free[F,R]
  case class Fail[F[_], R](error: Throwable) extends Free[F,R] {
    override def translate[G[_]](f: F ~> G): Free[G, R] = this.asInstanceOf[Free[G,R]]
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
        try f(x)
        catch { case e: Throwable => handleError(e) }
    }
    object Bound {
      def apply[F[_],X,R](fx: F[X], f: X => Free[F,R], onError: Option[Throwable => Free[F,R]]): Bound[F,X,R] =
        new Bound(fx, f, onError)
    }
    case class Done[F[_], R](r: R) extends ViewL[F,R]
    case class Failed[F[_],R](error: Throwable) extends ViewL[F,R]

    class Handler[F[_],R](val get: Option[Throwable => Free[F,R]]) extends AnyVal {
      def push(onErrInner: Throwable => Free[F,R]): Handler[F,R] = get match {
        case None => new Handler(Some(onErrInner))
        case Some(onErr) => new Handler(Some((e: Throwable) => OnError(Try(onErrInner(e)), onErr)))
      }
      def attachHandler(f: Free[F,R]): Free[F,R] = get match {
        case None => f
        case Some(h) => OnError(f, h)
      }
      def apply(err: Throwable): Free[F,R] = get match {
        case None => Fail(err)
        case Some(h) => try h(err) catch { case e: Throwable => Fail(e) }
      }
      def flatMap[R2](f: R => Free[F,R2]): Handler[F,R2] = get match {
        case None => new Handler(None)
        case Some(h) => new Handler(Some((e: Throwable) => h(e) flatMap f)) // todo: trampoline?
      }
    }
    object Handler { def empty[F[_],R]: Handler[F,R] = new Handler(None) }

    // Pure(x)
    // Bind(Eval(e), f)
    // OnError(Bind(Eval(e), f), h)

    def apply[F[_], R](free: Free[F, R]): ViewL[F, R] = {
      type FreeF[x] = Free[F,x]
      type X = Any
      @annotation.tailrec
      def go(free: Free[F, X], k: Option[X => Free[F,R]]
                             , onErr: Handler[F,R]
                             , onPureErr: Handler[F,R]): ViewL[F, R] = free match {
        case Pure(x) => k match {
          case None => ViewL.Done(x.asInstanceOf[R])
          case Some(f) => go(Try(f(x)).asInstanceOf[Free[F,X]], None, Handler.empty, Handler.empty)
        }
        case Eval(fx) => k match {
          case None => ViewL.Bound(fx, (x: X) => Free.Pure(x.asInstanceOf[R]), onErr.get)
          case Some(f) => ViewL.Bound(fx, f, onErr.get)
        }
        case Fail(err) =>
          go(onPureErr(err).asInstanceOf[Free[F,X]], None, Handler.empty, Handler.empty)
        case OnError(fx, h) => k match {
          case None => go(fx, None, onErr.push(h.asInstanceOf[Throwable => Free[F,R]]),
                                    onPureErr.push(h.asInstanceOf[Throwable => Free[F,R]]))
          // (a onError h) flatMap k2
          case Some(k2) =>
            val h2 = h.asInstanceOf[Throwable => Free[F,R]]
            go(fx, k, onErr.push(h2).flatMap(k2), onPureErr.push(h2).flatMap(k2))
        }
        case b: Free.Bind[F, _, X] =>
          val fw: Free[F, Any] = b.fx.asInstanceOf[Free[F, Any]]
          val f: Any => Free[F, X] = b.f.asInstanceOf[Any => Free[F, X]]
          k match {
            case None => go(fw,
              Some((x: X) => onErr.attachHandler(Try(f.asInstanceOf[X => Free[F,R]](x)))),
                Handler.empty,
                onPureErr)
            case Some(g) =>
              // keep onErr on the error handling stack in case there's an error in pure code
              go(fw, Some(w => onErr.attachHandler(Try(f(w)).flatMap(g))), Handler.empty, onErr)
          }
      }
      // inline use of `Try`, avoid constructing intermediate `Fail` objects
      // basic idea - onErr and onPureErr get pushed in event of OnError ctor
      // in event of a Bind, we attach the current onErr to the continuation and set onErr to empty,
      // but still keep onPureErr
      // idea being - if we are successful, the `Bound.f` continuation will reattach error handlers
      // so don't want to dup work
      go(free.asInstanceOf[Free[F,X]], None, Handler.empty, Handler.empty)
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
