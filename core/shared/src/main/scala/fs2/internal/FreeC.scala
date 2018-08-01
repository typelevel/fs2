package fs2.internal

import cats.{Monad, MonadError, ~>}
import cats.effect.{ExitCase, Sync}
import fs2.CompositeFailure
import FreeC._

import scala.annotation.tailrec
import scala.util.control.NonFatal

/** Free monad with a catch -- catches exceptions and provides mechanisms for handling them. */
private[fs2] sealed abstract class FreeC[F[_], +R] {

  def flatMap[R2](f: R => FreeC[F, R2]): FreeC[F, R2] =
    Bind[F, R, R2](
      this,
      e =>
        e match {
          case Result.Pure(r) =>
            try f(r)
            catch { case NonFatal(e) => FreeC.Result.Fail(e) }
          case Result.Interrupted(scope, err) => FreeC.Result.Interrupted(scope, err)
          case Result.Fail(e)                 => FreeC.Result.Fail(e)
      }
    )

  def transformWith[R2](f: Result[R] => FreeC[F, R2]): FreeC[F, R2] =
    Bind[F, R, R2](this,
                   r =>
                     try f(r)
                     catch { case NonFatal(e) => FreeC.Result.Fail(e) })

  def map[R2](f: R => R2): FreeC[F, R2] =
    Bind[F, R, R2](this, Result.monadInstance.map(_)(f).asFreeC[F])

  def handleErrorWith[R2 >: R](h: Throwable => FreeC[F, R2]): FreeC[F, R2] =
    Bind[F, R2, R2](this,
                    e =>
                      e match {
                        case Result.Fail(e) =>
                          try h(e)
                          catch { case NonFatal(e) => FreeC.Result.Fail(e) }
                        case other => other.covary[R2].asFreeC[F]
                    })

  def asHandler(e: Throwable): FreeC[F, R] = viewL.get match {
    case Result.Pure(_)  => Result.Fail(e)
    case Result.Fail(e2) => Result.Fail(CompositeFailure(e2, e))
    case Result.Interrupted(ctx, err) =>
      Result.Interrupted(ctx, err.map(t => CompositeFailure(e, t)).orElse(Some(e)))
    case Bind(_, k) => k(Result.Fail(e))
    case Eval(_)    => sys.error("impossible")
  }

  def viewL: ViewL[F, R] = mkViewL(this)

  def translate[G[_]](f: F ~> G): FreeC[G, R] = FreeC.suspend {
    viewL.get match {
      case Bind(fx, k) =>
        Bind(fx.translate(f), (e: Result[Any]) => k(e).translate(f))
      case Eval(fx)  => sys.error("impossible")
      case Result(r) => r.asFreeC[G]
    }
  }
}

private[fs2] object FreeC {

  def pure[F[_], A](a: A): FreeC[F, A] = Result.Pure(a)

  def eval[F[_], A](f: F[A]): FreeC[F, A] = Eval(f)

  def raiseError[F[_], A](rsn: Throwable): FreeC[F, A] = Result.Fail(rsn)

  def interrupted[F[_], X, A](interruptContext: X, failure: Option[Throwable]): FreeC[F, A] =
    Result.Interrupted(interruptContext, failure)

  sealed trait Result[+R] { self =>

    def asFreeC[F[_]]: FreeC[F, R] = self.asInstanceOf[FreeC[F, R]]

    def asExitCase: ExitCase[Throwable] = self match {
      case Result.Pure(_)           => ExitCase.Completed
      case Result.Fail(err)         => ExitCase.Error(err)
      case Result.Interrupted(_, _) => ExitCase.Canceled
    }

    def covary[R2 >: R]: Result[R2] = self

    def recoverWith[R2 >: R](f: Throwable => Result[R2]): Result[R2] = self match {
      case Result.Fail(err) =>
        try { f(err) } catch { case NonFatal(err2) => Result.Fail(CompositeFailure(err, err2)) }
      case _ => covary[R2]
    }

  }

  object Result {

    val unit: Result[Unit] = pure(())

    def pure[A](a: A): Result[A] = Result.Pure(a)

    def raiseError[A](rsn: Throwable): Result[A] = Result.Fail(rsn)

    def interrupted[A](scopeId: Token, failure: Option[Throwable]): Result[A] =
      Result.Interrupted(scopeId, failure)

    def fromEither[F[_], R](either: Either[Throwable, R]): Result[R] =
      either.fold(Result.Fail(_), Result.Pure(_))

    def unapply[F[_], R](freeC: FreeC[F, R]): Option[Result[R]] = freeC match {
      case r @ Result.Pure(_)           => Some(r: Result[R])
      case r @ Result.Fail(_)           => Some(r: Result[R])
      case r @ Result.Interrupted(_, _) => Some(r: Result[R])
      case _                            => None
    }

    final case class Pure[F[_], R](r: R) extends FreeC[F, R] with Result[R] {
      override def translate[G[_]](f: F ~> G): FreeC[G, R] =
        this.asInstanceOf[FreeC[G, R]]
      override def toString: String = s"FreeC.Pure($r)"
    }

    final case class Fail[F[_], R](error: Throwable) extends FreeC[F, R] with Result[R] {
      override def translate[G[_]](f: F ~> G): FreeC[G, R] =
        this.asInstanceOf[FreeC[G, R]]
      override def toString: String = s"FreeC.Fail($error)"
    }

    /**
      * Signals that FreeC evaluation was interrupted.
      *
      * @param context Any user specific context that needs to be captured during interruption
      *                for eventual resume of the operation.
      * @param deferredError Any errors, accumulated during resume of the interruption.
      */
    final case class Interrupted[F[_], X, R](context: X, deferredError: Option[Throwable])
        extends FreeC[F, R]
        with Result[R] {
      override def translate[G[_]](f: F ~> G): FreeC[G, R] =
        this.asInstanceOf[FreeC[G, R]]
      override def toString: String =
        s"FreeC.Interrupted($context, ${deferredError.map(_.getMessage)})"
    }

    val monadInstance: Monad[Result] = new Monad[Result] {
      def flatMap[A, B](fa: Result[A])(f: A => Result[B]): Result[B] = fa match {
        case Result.Pure(r) =>
          try { f(r) } catch { case NonFatal(err) => Result.Fail(err) }
        case failure @ Result.Fail(_)               => failure.asInstanceOf[Result[B]]
        case interrupted @ Result.Interrupted(_, _) => interrupted.asInstanceOf[Result[B]]
      }

      def tailRecM[A, B](a: A)(f: A => Result[Either[A, B]]): Result[B] = {
        @tailrec
        def go(a: A): Result[B] =
          f(a) match {
            case Result.Pure(Left(a))                   => go(a)
            case Result.Pure(Right(b))                  => Result.Pure(b)
            case failure @ Result.Fail(_)               => failure.asInstanceOf[Result[B]]
            case interrupted @ Result.Interrupted(_, _) => interrupted.asInstanceOf[Result[B]]
          }

        try { go(a) } catch { case t: Throwable => Result.Fail(t) }
      }

      def pure[A](x: A): Result[A] = Result.Pure(x)
    }

  }

  final case class Eval[F[_], R](fr: F[R]) extends FreeC[F, R] {
    override def translate[G[_]](f: F ~> G): FreeC[G, R] =
      try Eval(f(fr))
      catch { case NonFatal(t) => Result.Fail[G, R](t) }
    override def toString: String = s"FreeC.Eval($fr)"
  }
  final case class Bind[F[_], X, R](fx: FreeC[F, X], f: Result[X] => FreeC[F, R])
      extends FreeC[F, R] {
    override def toString: String = s"FreeC.Bind($fx, $f)"
  }

  def pureContinuation[F[_], R]: Result[R] => FreeC[F, R] =
    _.asFreeC[F]

  def suspend[F[_], R](fr: => FreeC[F, R]): FreeC[F, R] =
    Result.Pure[F, Unit](()).flatMap(_ => fr)

  /**
    * Unrolled view of a `FreeC` structure. The `get` value is guaranteed to be one of:
    * `Result(r)`,  `Bind(Eval(fx), k)`.
    */
  final class ViewL[F[_], +R](val get: FreeC[F, R]) extends AnyVal

  private def mkViewL[F[_], R](free: FreeC[F, R]): ViewL[F, R] = {
    @annotation.tailrec
    def go[X](free: FreeC[F, X]): ViewL[F, R] = free match {
      case Eval(fx) =>
        new ViewL(Bind(free.asInstanceOf[FreeC[F, R]], pureContinuation[F, R]))
      case b: FreeC.Bind[F, y, X] =>
        b.fx match {
          case Result(r)  => go(b.f(r))
          case Eval(_)    => new ViewL(b.asInstanceOf[FreeC[F, R]])
          case Bind(w, g) => go(Bind(w, (e: Result[Any]) => Bind(g(e), b.f)))
        }
      case Result(r) => new ViewL(r.asInstanceOf[FreeC[F, R]])
    }
    go(free)
  }

  implicit final class InvariantOps[F[_], R](private val self: FreeC[F, R]) extends AnyVal {
    // None indicates the FreeC was interrupted
    def run(implicit F: MonadError[F, Throwable]): F[Option[R]] =
      self.viewL.get match {
        case Result.Pure(r)             => F.pure(Some(r))
        case Result.Fail(e)             => F.raiseError(e)
        case Result.Interrupted(_, err) => err.fold[F[Option[R]]](F.pure(None)) { F.raiseError }
        case Bind(fr, k) =>
          F.flatMap(F.attempt(fr.asInstanceOf[Eval[F, Any]].fr)) { r =>
            k(Result.fromEither(r)).run
          }
        case Eval(_) => sys.error("impossible")
      }
  }

  implicit def syncInstance[F[_]]: Sync[FreeC[F, ?]] = new Sync[FreeC[F, ?]] {
    def pure[A](a: A): FreeC[F, A] = FreeC.Result.Pure(a)
    def handleErrorWith[A](fa: FreeC[F, A])(f: Throwable => FreeC[F, A]): FreeC[F, A] =
      fa.handleErrorWith(f)
    def raiseError[A](t: Throwable): FreeC[F, A] = FreeC.Result.Fail(t)
    def flatMap[A, B](fa: FreeC[F, A])(f: A => FreeC[F, B]): FreeC[F, B] =
      fa.flatMap(f)
    def tailRecM[A, B](a: A)(f: A => FreeC[F, Either[A, B]]): FreeC[F, B] =
      f(a).flatMap {
        case Left(a)  => tailRecM(a)(f)
        case Right(b) => pure(b)
      }
    def suspend[A](thunk: => FreeC[F, A]): FreeC[F, A] = FreeC.suspend(thunk)
    def bracketCase[A, B](acquire: FreeC[F, A])(use: A => FreeC[F, B])(
        release: (A, ExitCase[Throwable]) => FreeC[F, Unit]): FreeC[F, B] =
      acquire.flatMap { a =>
        val used =
          try use(a)
          catch { case NonFatal(t) => FreeC.Result.Fail[F, B](t) }
        used.transformWith { result =>
          release(a, result.asExitCase).transformWith {
            case Result.Fail(t2) =>
              result
                .recoverWith { t =>
                  Result.Fail(CompositeFailure(t, t2))
                }
                .asFreeC[F]
            case _ => result.asFreeC[F]
          }
        }
      }
  }
}
