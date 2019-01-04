package fs2.internal

import cats.{MonadError, ~>}
import cats.effect.{ExitCase, Sync}
import fs2.{CompositeFailure, INothing}
import FreeC._

import scala.annotation.tailrec
import scala.util.control.NonFatal

/**
  * Free Monad with Catch (and Interruption).
  *
  * [[FreeC]] provides mechanism for ensuring stack safety and capturing any exceptions that may arise during computation.
  *
  * Furthermore, it may capture Interruption of the evaluation, although [[FreeC]] itself does not have any
  * interruptible behaviour per se.
  *
  * Interruption cause may be captured in [[FreeC.Result.Interrupted]] and allows user to pass along any information relevant
  * to interpreter.
  *
  * Typically the [[FreeC]] user provides interpretation of FreeC in form of [[ViewL]] structure, that allows to step
  * FreeC via series of Results ([[Result.Pure]], [[Result.Fail]] and [[Result.Interrupted]]) and FreeC step ([[ViewL.View]])
  */
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
    Bind[F, R, R2](this, Result.map(_)(f).asFreeC[F])

  def handleErrorWith[R2 >: R](h: Throwable => FreeC[F, R2]): FreeC[F, R2] =
    Bind[F, R2, R2](this,
                    e =>
                      e match {
                        case Result.Fail(e) =>
                          try h(e)
                          catch { case NonFatal(e) => FreeC.Result.Fail(e) }
                        case other => other.asFreeC[F]
                    })

  def asHandler(e: Throwable): FreeC[F, R] = ViewL(this) match {
    case Result.Pure(_)  => Result.Fail(e)
    case Result.Fail(e2) => Result.Fail(CompositeFailure(e2, e))
    case Result.Interrupted(ctx, err) =>
      Result.Interrupted(ctx, err.map(t => CompositeFailure(e, t)).orElse(Some(e)))
    case ViewL.View(_, k) => k(Result.Fail(e))
  }

  def viewL[R2 >: R]: ViewL[F, R2] = ViewL(this)

  def translate[G[_]](f: F ~> G): FreeC[G, R] = suspend {
    viewL match {
      case ViewL.View(fx, k) =>
        Bind(Eval(fx).translate(f), (e: Result[Any]) => k(e).translate(f))
      case r @ Result.Pure(_)           => r.asFreeC[G]
      case r @ Result.Fail(_)           => r.asFreeC[G]
      case r @ Result.Interrupted(_, _) => r.asFreeC[G]
    }
  }
}

private[fs2] object FreeC {

  def unit[F[_]]: FreeC[F, Unit] = Result.unit.asFreeC

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

    def recoverWith[R2 >: R](f: Throwable => Result[R2]): Result[R2] = self match {
      case Result.Fail(err) =>
        try { f(err) } catch { case NonFatal(err2) => Result.Fail(CompositeFailure(err, err2)) }
      case _ => self
    }

  }

  object Result {

    val unit: Result[Unit] = pure(())

    def pure[A](a: A): Result[A] = Result.Pure(a)

    def raiseError(rsn: Throwable): Result[INothing] = Result.Fail(rsn)

    def interrupted(scopeId: Token, failure: Option[Throwable]): Result[INothing] =
      Result.Interrupted(scopeId, failure)

    def fromEither[R](either: Either[Throwable, R]): Result[R] =
      either.fold(Result.Fail(_), Result.Pure(_))

    def unapply[F[_], R](freeC: FreeC[F, R]): Option[Result[R]] = freeC match {
      case r @ Result.Pure(_)           => Some(r: Result[R])
      case r @ Result.Fail(_)           => Some(r: Result[R])
      case r @ Result.Interrupted(_, _) => Some(r: Result[R])
      case _                            => None
    }

    final case class Pure[F[_], R](r: R) extends FreeC[F, R] with Result[R] with ViewL[F, R] {
      override def translate[G[_]](f: F ~> G): FreeC[G, R] =
        this.asInstanceOf[FreeC[G, R]]
      override def toString: String = s"FreeC.Pure($r)"
    }

    final case class Fail[F[_]](error: Throwable)
        extends FreeC[F, INothing]
        with Result[INothing]
        with ViewL[F, INothing] {
      override def translate[G[_]](f: F ~> G): FreeC[G, INothing] =
        this.asInstanceOf[FreeC[G, INothing]]
      override def toString: String = s"FreeC.Fail($error)"
    }

    /**
      * Signals that FreeC evaluation was interrupted.
      *
      * @param context Any user specific context that needs to be captured during interruption
      *                for eventual resume of the operation.
      *
      * @param deferredError Any errors, accumulated during resume of the interruption.
      *                      Instead throwing errors immediately during interruption,
      *                      signalling of the errors may be deferred until the Interruption resumes.
      */
    final case class Interrupted[F[_], X](context: X, deferredError: Option[Throwable])
        extends FreeC[F, INothing]
        with Result[INothing]
        with ViewL[F, INothing] {
      override def translate[G[_]](f: F ~> G): FreeC[G, INothing] =
        this.asInstanceOf[FreeC[G, INothing]]
      override def toString: String =
        s"FreeC.Interrupted($context, ${deferredError.map(_.getMessage)})"
    }

    private[FreeC] def map[A, B](fa: Result[A])(f: A => B): Result[B] = fa match {
      case Result.Pure(r) =>
        try { Result.Pure(f(r)) } catch { case NonFatal(err) => Result.Fail(err) }
      case failure @ Result.Fail(_)               => failure.asInstanceOf[Result[B]]
      case interrupted @ Result.Interrupted(_, _) => interrupted.asInstanceOf[Result[B]]
    }

  }

  final case class Eval[F[_], R](fr: F[R]) extends FreeC[F, R] {
    override def translate[G[_]](f: F ~> G): FreeC[G, R] =
      suspend {
        try Eval(f(fr))
        catch { case NonFatal(t) => Result.Fail[G](t) }
      }
    override def toString: String = s"FreeC.Eval($fr)"
  }
  final case class Bind[F[_], X, R](fx: FreeC[F, X], f: Result[X] => FreeC[F, R])
      extends FreeC[F, R] {
    override def toString: String = s"FreeC.Bind($fx, $f)"
  }

  def pureContinuation[F[_], R]: Result[R] => FreeC[F, R] =
    _.asFreeC[F]

  def suspend[F[_], R](fr: => FreeC[F, R]): FreeC[F, R] =
    unit.flatMap(_ => fr)

  /**
    * Unrolled view of a `FreeC` structure. may be `Result` or `EvalBind`
    */
  sealed trait ViewL[F[_], +R]

  object ViewL {

    /** unrolled view of FreeC `bind` structure **/
    final case class View[F[_], X, R](step: F[X], next: Result[X] => FreeC[F, R])
        extends ViewL[F, R]

    private[fs2] def apply[F[_], R](free: FreeC[F, R]): ViewL[F, R] = mk(free)

    @tailrec
    private def mk[F[_], R](free: FreeC[F, R]): ViewL[F, R] =
      free match {
        case Eval(fx) => View(fx, pureContinuation[F, R])
        case b: FreeC.Bind[F, y, R] =>
          b.fx match {
            case Result(r)  => mk(b.f(r))
            case Eval(fr)   => ViewL.View(fr, b.f)
            case Bind(w, g) => mk(Bind(w, (e: Result[Any]) => Bind(g(e), b.f)))
          }
        case r @ Result.Pure(_)           => r
        case r @ Result.Fail(_)           => r
        case r @ Result.Interrupted(_, _) => r
      }

  }

  implicit final class InvariantOps[F[_], R](private val self: FreeC[F, R]) extends AnyVal {
    // None indicates the FreeC was interrupted
    def run(implicit F: MonadError[F, Throwable]): F[Option[R]] =
      self.viewL match {
        case Result.Pure(r)             => F.pure(Some(r))
        case Result.Fail(e)             => F.raiseError(e)
        case Result.Interrupted(_, err) => err.fold[F[Option[R]]](F.pure(None)) { F.raiseError }
        case ViewL.View(step, next) =>
          F.flatMap(F.attempt(step)) { r =>
            next(Result.fromEither(r)).run
          }
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
          catch { case NonFatal(t) => FreeC.Result.Fail[F](t) }
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
