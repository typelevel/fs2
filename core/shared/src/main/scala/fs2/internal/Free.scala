package fs2.internal

import Free.ViewL
import Free.ViewL._
import Free._

import cats.{ ~>, MonadError }

sealed abstract class Free[F[_], +R] {

  def flatMap[R2](f: R => Free[F, R2]): Free[F, R2] = Bind(this, f)
  def map[R2](f: R => R2): Free[F,R2] = Bind(this, (r: R) => Free.Pure(f(r)))
  def onError[R2>:R](h: Throwable => Free[F,R2]): Free[F,R2] = OnError(this, h)
  lazy val viewL: ViewL[F,R] = ViewL(this) // todo - review this
  def translate[G[_]](f: F ~> G): Free[G, R] = this.viewL match {
    case Done(r) => Pure(r)
    case b: Bound[F,_,R] => b.translate(f) match {
      case Done(r) => Pure(r)
      case Failed(err) => Fail(err)
      case b: Bound[G,_,R] => b.toFree
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
    class Bound[F[_], X, R] private (val fx: F[X], k: Continuation[F,X,R]) extends ViewL[F, R] {
      def hasErrorHandler = k.hasErrorHandler
      def handleError(e: Throwable): Free[F,R] = k(Fail(e))
      def tryBind: X => Free[F,R] = x => k(Pure(x))
      def toFree: Free[F,R] = k(Eval(fx))
      def translate[G[_]](f: F ~> G): ViewL[G,R] =
        try Bound(f(fx), k.translate(f))
        catch { case NonFatal(e) => k(Fail(e)).translate(f).viewL }
    }
    object Bound {
      def apply[F[_],X,R](fx: F[X], k: Continuation[F,X,R]): Bound[F,X,R] =
        new Bound(fx, k)
    }
    case class Done[F[_], R](r: R) extends ViewL[F,R]
    case class Failed[F[_],R](error: Throwable) extends ViewL[F,R]

    abstract class Continuation[F[_],A,R] {
      def apply(f: Free[F,A]): Free[F,R]
      def compose[A0](k: Continuation[F,A0,A]): Continuation[F,A0,R] = Continuation.composed(k, this)
      def hasErrorHandler: Boolean
      def translate[G[_]](nt: F ~> G): Continuation[G,A,R]
      def depth = 0
    }
    object Continuation {
      case class OnError[F[_],A](h: Throwable => Free[F,A]) extends Continuation[F,A,A] {
        def apply(f: Free[F,A]): Free[F,A] = f match {
          case Free.Fail(e) => try h(e) catch { case NonFatal(e) => Fail(e) }
          case Free.Pure(x) => f
          case _ => f onError h
        }
        override def compose[A0](k: Continuation[F,A0,A]) = k match {
          case OnError(hi) =>
            OnError[F,A0]((e: Throwable) => hi(e) onError h.asInstanceOf[Throwable => Free[F,A0]])
              .asInstanceOf[Continuation[F,A0,A]]
          case _ => composed(k, this)
        }
        def hasErrorHandler = true
        def translate[G[_]](nt: F ~> G): Continuation[G,A,A] =
          OnError((e: Throwable) => h(e).translate(nt))
      }
      def composed[F[_],A,B,C](f: Continuation[F,A,B], g: Continuation[F,B,C]): Continuation[F,A,C]
        = new Continuation[F,A,C] {
          val hasErrorHandler = f.hasErrorHandler || g.hasErrorHandler
          override val depth = (f.depth max g.depth) + 1
          def apply(a: Free[F,A]) =
            if (depth < 25) g(f(a))
            else Free.Pure(()) flatMap { _ => g(f(a)) }
          def translate[G[_]](nt: F ~> G): Continuation[G,A,C] =
            composed(f.translate(nt), g.translate(nt))
        }

      case class FlatMap[F[_],A,B](f: A => Free[F,B]) extends Continuation[F,A,B] {
        def apply(a: Free[F,A]): Free[F,B] = a match {
          case Free.Fail(_) => a.asInstanceOf[Free[F,B]]
          case Free.Pure(a) => try f(a) catch { case NonFatal(e) => Fail(e) }
          case _ => a flatMap f
        }
        override def compose[A0](k: Continuation[F,A0,A]): Continuation[F,A0,B] = k match {
          case FlatMap(g) => FlatMap((a0: A0) => g(a0) flatMap f)
          case _ => Continuation.composed(k, this)
        }
        def translate[G[_]](nt: F ~> G): Continuation[G,A,B] =
          FlatMap((a: A) => f(a).translate(nt))
        def hasErrorHandler = false
      }
      case class Done[F[_],A](proof: A =:= A) extends Continuation[F,A,A] {
        def apply(a: Free[F,A]): Free[F,A] = a
        def hasErrorHandler = false
        override def compose[A0](k: Continuation[F,A0,A]) = k
        def translate[G[_]](nt: F ~> G): Continuation[G,A,A] = this.asInstanceOf[Continuation[G,A,A]]
      }
      def done[F[_],A](implicit eq: A =:= A) = Done(eq)
      def flatMap[F[_],A,B](f: A => Free[F,B]) = FlatMap(f)
      def onError[F[_],A](h: Throwable => Free[F,A]) = OnError(h)
    }

    // Pure(x)
    // Eval(e)
    // Bind(Eval(e), f)
    // OnError(Bind(Eval(e), f), h)
    // Bind(OnError(Eval(e), h), f)

    def apply[F[_], R](free: Free[F, R]): ViewL[F, R] = {
      type FreeF[x] = Free[F,x]
      type X = Any
      @annotation.tailrec
      def go(free: Free[F, X], k: Continuation[F,X,R]): ViewL[F, R] = free match {
        case Pure(x) => k match {
          case d: Continuation.Done[F,Any] => ViewL.Done(x.asInstanceOf[R])
          case k => go(k(free), Continuation.done[F,X].asInstanceOf[Continuation[F,X,R]])
        }
        case Eval(fx) => ViewL.Bound(fx, k)
        case Fail(err) => k match {
          case d: Continuation.Done[F,Any] => ViewL.Failed(err)
          case k => go(k(free), Continuation.done[F,X].asInstanceOf[Continuation[F,X,R]])
        }
        case OnError(fx, h) => go(fx, Continuation.composed(Continuation.onError(h), k))
        case b: Free.Bind[F, _, X] =>
          val fw: Free[F, Any] = b.fx.asInstanceOf[Free[F, Any]]
          val f: Any => Free[F, X] = b.f.asInstanceOf[Any => Free[F, X]]
          go(fw, Continuation.composed(Continuation.flatMap(f), k))
      }
      go(free.asInstanceOf[Free[F,X]], Continuation.done[F,X].asInstanceOf[Continuation[F,X,R]])
    }
  }

  implicit class FreeRunOps[F[_],R](val self: Free[F,R]) extends AnyVal {
    def run(implicit F: MonadError[F, Throwable]): F[R] = {
      self.viewL match {
        case Done(r) => F.pure(r)
        case Failed(t) => F.raiseError(t)
        case b: Bound[F,_,R] => F.flatMap(F.attempt(b.fx)) {
          case Left(err) => b.handleError(err).run
          case Right(x) => b.tryBind(x).run
        }
      }
    }
  }
}
