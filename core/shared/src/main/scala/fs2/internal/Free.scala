package fs2.internal

import Free.ViewL
import Free._

import cats.{ ~>, MonadError }
import cats.effect.Sync

private[fs2] sealed abstract class Free[F[_], +R] {

  def flatMap[R2](f: R => Free[F, R2]): Free[F, R2] =
    Bind[F,R,R2](this, e => e match {
      case Right(r) => try f(r) catch { case NonFatal(e) => Free.Fail(e) }
      case Left(e) => Free.Fail(e)
    })

  def map[R2](f: R => R2): Free[F,R2] =
    Bind(this, (r: Either[Throwable,R]) => r match {
      case Right(r) => try Free.Pure(f(r)) catch { case NonFatal(e) => Free.Fail(e) }
      case Left(e) => Free.Fail(e)
    })

  def onError[R2>:R](h: Throwable => Free[F,R2]): Free[F,R2] =
    Bind[F,R2,R2](this, e => e match {
      case Right(a) => Free.Pure(a)
      case Left(e) => try h(e) catch { case NonFatal(e) => Free.Fail(e) } })

  def asHandler(e: Throwable): Free[F,R] = viewL.get match {
    case Pure(_) => Fail(e)
    case Fail(e2) => Fail(e)
    case Bind(_, k) => k(Left(e))
    case Eval(_) => sys.error("impossible")
  }

  private var cachedViewL: Option[ViewL[F,R @annotation.unchecked.uncheckedVariance]] = None
  def viewL: ViewL[F,R] = {
    cachedViewL match {
      case Some(v) => v
      case None =>
        val v = ViewL(this)
        cachedViewL = Some(v) // OK to race multiple threads here
        v
    }
  }

  def translate[G[_]](f: F ~> G): Free[G, R] = Free.suspend {
    viewL.get match {
      case Pure(r) => Pure(r)
      case Bind(fx, k) => Bind(fx translate f, k andThen (_ translate f))
      case Fail(e) => Fail(e)
      case Eval(fx) => Eval(f(fx))
    }
  }
}

private[fs2] object Free {
  final case class Pure[F[_], R](r: R) extends Free[F, R] {
    override def translate[G[_]](f: F ~> G): Free[G, R] = this.asInstanceOf[Free[G,R]]
  }
  final case class Eval[F[_], R](fr: F[R]) extends Free[F, R] {
    override def translate[G[_]](f: F ~> G): Free[G, R] = Eval(f(fr))
  }
  final case class Bind[F[_], X, R](fx: Free[F, X], f: Either[Throwable,X] => Free[F, R]) extends Free[F, R]
  final case class Fail[F[_], R](error: Throwable) extends Free[F,R] {
    override def translate[G[_]](f: F ~> G): Free[G, R] = this.asInstanceOf[Free[G,R]]
  }

  private val pureContinuation_ = (e: Either[Throwable,Any]) => e match {
    case Right(r) => Pure[Any,Any](r)
    case Left(e) => Fail[Any,Any](e)
  }

  def pureContinuation[F[_],R]: Either[Throwable,R] => Free[F,R] =
    pureContinuation_.asInstanceOf[Either[Throwable,R] => Free[F,R]]

  def suspend[F[_], R](fr: Free[F, R]): Free[F, R] =
    Pure[F, Unit](()).flatMap(_ => fr)

  // Pure(r), Fail(e), Bind(Eval(fx), k),
  final class ViewL[F[_],+R](val get: Free[F,R]) extends AnyVal
  object ViewL {
    def apply[F[_], R](free: Free[F, R]): ViewL[F, R] = {
      type X = Any
      @annotation.tailrec
      def go(free: Free[F, X]): ViewL[F, R] = free match {
        case Pure(x) => new ViewL(free.asInstanceOf[Free[F,R]])
        case Eval(fx) => new ViewL(Bind(free.asInstanceOf[Free[F,R]], pureContinuation[F,R]))
        case Fail(err) => new ViewL(free.asInstanceOf[Free[F,R]])
        case b: Free.Bind[F, _, X] =>
          val fw: Free[F, Any] = b.fx.asInstanceOf[Free[F, Any]]
          val f: Either[Throwable,Any] => Free[F, X] = b.f.asInstanceOf[Either[Throwable,Any] => Free[F, X]]
          fw match {
            case Pure(x) => go(f(Right(x)))
            case Fail(e) => go(f(Left(e)))
            case Eval(_) => new ViewL(b.asInstanceOf[Free[F,R]])
            case Bind(w, g) => go(Bind(w, kcompose(g, f)))
          }
      }
      go(free.asInstanceOf[Free[F,X]])
    }
  }

  // todo suspend
  private def kcompose[F[_],A,B,C](
    a: Either[Throwable,A] => Free[F,B],
    b: Either[Throwable,B] => Free[F,C]): Either[Throwable,A] => Free[F,C] =
    e => Bind(a(e), b)

  implicit class FreeRunOps[F[_],R](val self: Free[F,R]) extends AnyVal {
    def run(implicit F: MonadError[F, Throwable]): F[R] =
      self.viewL.get match {
        case Pure(r) => F.pure(r)
        case Fail(e) => F.raiseError(e)
        case Bind(fr, k) =>
          F.flatMap(F.attempt(fr.asInstanceOf[Eval[F,Any]].fr)) { e => k(e).run }
        case Eval(_) => sys.error("impossible")
      }
    }

  implicit def syncInstance[F[_]]: Sync[Free[F,?]] = new Sync[Free[F,?]] {
    def pure[A](a: A): Free[F,A] = Free.Pure(a)
    def handleErrorWith[A](fa: Free[F,A])(f: Throwable => Free[F,A]): Free[F,A] = fa.onError(f)
    def raiseError[A](t: Throwable): Free[F,A] = Free.Fail(t)
    def flatMap[A,B](fa: Free[F,A])(f: A => Free[F,B]): Free[F,B] = fa.flatMap(f)
    def tailRecM[A,B](a: A)(f: A => Free[F,Either[A,B]]): Free[F,B] = f(a).flatMap {
      case Left(a) => tailRecM(a)(f)
      case Right(b) => pure(b)
    }
    def suspend[A](thunk: => Free[F,A]): Free[F,A] = Free.suspend(thunk)
  }
}
