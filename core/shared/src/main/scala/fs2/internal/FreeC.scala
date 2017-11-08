package fs2.internal

import cats.{ ~>, MonadError }
import cats.effect.Sync

import FreeC._

/** Free monad with a catch -- catches exceptions and provides mechanisms for handling them. */
private[fs2] sealed abstract class FreeC[F[_], +R] {

  def flatMap[R2](f: R => FreeC[F, R2]): FreeC[F, R2] =
    Bind[F,R,R2](this, e => e match {
      case Right(r) => try f(r) catch { case NonFatal(e) => FreeC.Fail(e) }
      case Left(e) => FreeC.Fail(e)
    })

  def map[R2](f: R => R2): FreeC[F,R2] =
    Bind(this, (r: Either[Throwable,R]) => r match {
      case Right(r) => try FreeC.Pure(f(r)) catch { case NonFatal(e) => FreeC.Fail(e) }
      case Left(e) => FreeC.Fail(e)
    })

  def handleErrorWith[R2>:R](h: Throwable => FreeC[F,R2]): FreeC[F,R2] =
    Bind[F,R2,R2](this, e => e match {
      case Right(a) => FreeC.Pure(a)
      case Left(e) => try h(e) catch { case NonFatal(e) => FreeC.Fail(e) } })

  def asHandler(e: Throwable): FreeC[F,R] = viewL.get match {
    case Pure(_) => Fail(e)
    case Fail(e2) => Fail(e)
    case Bind(_, k) => k(Left(e))
    case Eval(_) => sys.error("impossible")
  }

  def viewL: ViewL[F,R] = mkViewL(this)

  def translate[G[_]](f: F ~> G): FreeC[G, R] = FreeC.suspend {
    viewL.get match {
      case Pure(r) => Pure(r)
      case Bind(fx, k) => Bind(fx translate f, (e: Either[Throwable,Any]) => k(e).translate(f))
      case Fail(e) => Fail(e)
      case Eval(fx) => Eval(f(fx))
    }
  }
}

private[fs2] object FreeC {
  final case class Pure[F[_], R](r: R) extends FreeC[F, R] {
    override def translate[G[_]](f: F ~> G): FreeC[G, R] = this.asInstanceOf[FreeC[G,R]]
  }
  final case class Eval[F[_], R](fr: F[R]) extends FreeC[F, R] {
    override def translate[G[_]](f: F ~> G): FreeC[G, R] = Eval(f(fr))
  }
  final case class Bind[F[_], X, R](fx: FreeC[F, X], f: Either[Throwable,X] => FreeC[F, R]) extends FreeC[F, R]
  final case class Fail[F[_], R](error: Throwable) extends FreeC[F,R] {
    override def translate[G[_]](f: F ~> G): FreeC[G, R] = this.asInstanceOf[FreeC[G,R]]
  }

  private val pureContinuation_ = (e: Either[Throwable,Any]) => e match {
    case Right(r) => Pure[Any,Any](r)
    case Left(e) => Fail[Any,Any](e)
  }

  def pureContinuation[F[_],R]: Either[Throwable,R] => FreeC[F,R] =
    pureContinuation_.asInstanceOf[Either[Throwable,R] => FreeC[F,R]]

  def suspend[F[_], R](fr: => FreeC[F, R]): FreeC[F, R] =
    Pure[F, Unit](()).flatMap(_ => fr)

  /**
   * Unrolled view of a `FreeC` structure. The `get` value is guaranteed to be one of:
   * `Pure(r)`, `Fail(e)`, `Bind(Eval(fx), k)`.
   */
  final class ViewL[F[_],+R](val get: FreeC[F,R]) extends AnyVal

  private def mkViewL[F[_], R](free: FreeC[F, R]): ViewL[F, R] = {
    type X = Any
    @annotation.tailrec
    def go(free: FreeC[F, X]): ViewL[F, R] = free match {
      case Pure(x) => new ViewL(free.asInstanceOf[FreeC[F,R]])
      case Eval(fx) => new ViewL(Bind(free.asInstanceOf[FreeC[F,R]], pureContinuation[F,R]))
      case Fail(err) => new ViewL(free.asInstanceOf[FreeC[F,R]])
      case b: FreeC.Bind[F, _, X] =>
        val fw: FreeC[F, Any] = b.fx.asInstanceOf[FreeC[F, Any]]
        val f: Either[Throwable,Any] => FreeC[F, X] = b.f.asInstanceOf[Either[Throwable,Any] => FreeC[F, X]]
        fw match {
          case Pure(x) => go(f(Right(x)))
          case Fail(e) => go(f(Left(e)))
          case Eval(_) => new ViewL(b.asInstanceOf[FreeC[F,R]])
          case Bind(w, g) => go(Bind(w, (e: Either[Throwable,X]) => Bind(g(e), f)))
        }
    }
    go(free.asInstanceOf[FreeC[F,X]])
  }

  implicit final class InvariantOps[F[_],R](private val self: FreeC[F,R]) extends AnyVal {
    def run(implicit F: MonadError[F, Throwable]): F[R] =
      self.viewL.get match {
        case Pure(r) => F.pure(r)
        case Fail(e) => F.raiseError(e)
        case Bind(fr, k) =>
          F.flatMap(F.attempt(fr.asInstanceOf[Eval[F,Any]].fr)) { e => k(e).run }
        case Eval(_) => sys.error("impossible")
      }
    }

  implicit def syncInstance[F[_]]: Sync[FreeC[F,?]] = new Sync[FreeC[F,?]] {
    def pure[A](a: A): FreeC[F,A] = FreeC.Pure(a)
    def handleErrorWith[A](fa: FreeC[F,A])(f: Throwable => FreeC[F,A]): FreeC[F,A] = fa.handleErrorWith(f)
    def raiseError[A](t: Throwable): FreeC[F,A] = FreeC.Fail(t)
    def flatMap[A,B](fa: FreeC[F,A])(f: A => FreeC[F,B]): FreeC[F,B] = fa.flatMap(f)
    def tailRecM[A,B](a: A)(f: A => FreeC[F,Either[A,B]]): FreeC[F,B] = f(a).flatMap {
      case Left(a) => tailRecM(a)(f)
      case Right(b) => pure(b)
    }
    def suspend[A](thunk: => FreeC[F,A]): FreeC[F,A] = FreeC.suspend(thunk)
  }
}
