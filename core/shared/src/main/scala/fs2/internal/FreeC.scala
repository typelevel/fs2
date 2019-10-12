package fs2.internal

import cats.effect.ExitCase
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
private[fs2] abstract class FreeC[F[_], +O, +R] {

  def flatMap[O2 >: O, R2](f: R => FreeC[F, O2, R2]): FreeC[F, O2, R2] =
    new Bind[F, O2, R, R2](this) {
      def cont(e: Result[R]): FreeC[F, O2, R2] = e match {
        case Result.Pure(r) =>
          try f(r)
          catch { case NonFatal(e) => FreeC.Result.Fail(e) }
        case Result.Interrupted(scope, err) => FreeC.Result.Interrupted(scope, err)
        case Result.Fail(e)                 => FreeC.Result.Fail(e)
      }
    }

  def append[O2 >: O, R2](post: => FreeC[F, O2, R2]): FreeC[F, O2, R2] =
    new Bind[F, O2, R, R2](this) {
      def cont(r: Result[R]): FreeC[F, O2, R2] = r match {
        case _: Result.Pure[F, _]        => post
        case r: Result.Interrupted[F, _] => r
        case r: Result.Fail[F]           => r
      }
    }

  def transformWith[O2 >: O, R2](f: Result[R] => FreeC[F, O2, R2]): FreeC[F, O2, R2] =
    new Bind[F, O2, R, R2](this) {
      def cont(r: Result[R]): FreeC[F, O2, R2] =
        try f(r)
        catch { case NonFatal(e) => FreeC.Result.Fail(e) }
    }

  def map[O2 >: O, R2](f: R => R2): FreeC[F, O2, R2] =
    new Bind[F, O2, R, R2](this) {
      def cont(e: Result[R]): FreeC[F, O2, R2] = Result.map(e)(f).asFreeC[F]
    }

  def handleErrorWith[O2 >: O, R2 >: R](h: Throwable => FreeC[F, O2, R2]): FreeC[F, O2, R2] =
    new Bind[F, O2, R2, R2](this) {
      def cont(e: Result[R2]): FreeC[F, O2, R2] = e match {
        case Result.Fail(e) =>
          try h(e)
          catch { case NonFatal(e) => FreeC.Result.Fail(e) }
        case other => other.asFreeC[F]
      }
    }

  def asHandler(e: Throwable): FreeC[F, O, R] = ViewL(this) match {
    case Result.Pure(_)  => Result.Fail(e)
    case Result.Fail(e2) => Result.Fail(CompositeFailure(e2, e))
    case Result.Interrupted(ctx, err) =>
      Result.Interrupted(ctx, err.map(t => CompositeFailure(e, t)).orElse(Some(e)))
    case v @ ViewL.View(_) => v.next(Result.Fail(e))
  }

  def viewL[O2 >: O, R2 >: R]: ViewL[F, O2, R2] = ViewL(this)

  def mapOutput[P](f: O => P): FreeC[F, P, R]
}

private[fs2] object FreeC {

  sealed trait Result[+R] { self =>

    def asFreeC[F[_]]: FreeC[F, INothing, R] = self.asInstanceOf[FreeC[F, INothing, R]]

    def asExitCase: ExitCase[Throwable] = self match {
      case Result.Pure(_)           => ExitCase.Completed
      case Result.Fail(err)         => ExitCase.Error(err)
      case Result.Interrupted(_, _) => ExitCase.Canceled
    }

  }

  sealed abstract class ResultC[F[_], +R]
      extends FreeC[F, INothing, R]
      with ViewL[F, INothing, R]
      with Result[R] {
    override def mapOutput[P](f: INothing => P): FreeC[F, P, R] = this
  }

  object Result {

    val unit: Result[Unit] = Result.Pure(())

    def fromEither[R](either: Either[Throwable, R]): Result[R] =
      either.fold(Result.Fail(_), Result.Pure(_))

    def unapply[F[_], R](freeC: FreeC[F, _, R]): Option[Result[R]] = freeC match {
      case r @ Result.Pure(_)           => Some(r: Result[R])
      case r @ Result.Fail(_)           => Some(r: Result[R])
      case r @ Result.Interrupted(_, _) => Some(r: Result[R])
      case _                            => None
    }

    final case class Pure[F[_], R](r: R) extends ResultC[F, R] {
      override def toString: String = s"FreeC.Pure($r)"
    }

    final case class Fail[F[_]](error: Throwable) extends ResultC[F, INothing] {
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
        extends ResultC[F, INothing] {
      override def toString: String =
        s"FreeC.Interrupted($context, ${deferredError.map(_.getMessage)})"
    }

    private[FreeC] def map[A, B](fa: Result[A])(f: A => B): Result[B] = fa match {
      case Result.Pure(r) =>
        try Result.Pure(f(r))
        catch { case NonFatal(err) => Result.Fail(err) }
      case failure @ Result.Fail(_)               => failure.asInstanceOf[Result[B]]
      case interrupted @ Result.Interrupted(_, _) => interrupted.asInstanceOf[Result[B]]
    }

  }

  abstract class Eval[F[_], +O, +R] extends FreeC[F, O, R]

  abstract class Bind[F[_], O, X, R](val step: FreeC[F, O, X]) extends FreeC[F, O, R] {
    def cont(r: Result[X]): FreeC[F, O, R]
    def delegate: Bind[F, O, X, R] = this

    override def mapOutput[P](f: O => P): FreeC[F, P, R] = suspend {
      viewL match {
        case v: ViewL.View[F, O, x, R] =>
          new Bind[F, P, x, R](v.step.mapOutput(f)) {
            def cont(e: Result[x]) = v.next(e).mapOutput(f)
          }
        case r @ Result.Pure(_)           => r
        case r @ Result.Fail(_)           => r
        case r @ Result.Interrupted(_, _) => r
      }
    }

    override def toString: String = s"FreeC.Bind($step)"
  }

  def suspend[F[_], O, R](fr: => FreeC[F, O, R]): FreeC[F, O, R] =
    new Bind[F, O, Unit, R](Result.unit.asFreeC[F]) {
      def cont(r: Result[Unit]): FreeC[F, O, R] = fr
    }

  /**
    * Unrolled view of a `FreeC` structure. may be `Result` or `EvalBind`
    */
  sealed trait ViewL[F[_], +O, +R]

  object ViewL {

    /** unrolled view of FreeC `bind` structure **/
    sealed abstract case class View[F[_], O, X, R](step: Eval[F, O, X]) extends ViewL[F, O, R] {
      def next(r: Result[X]): FreeC[F, O, R]
    }

    private[ViewL] final class EvalView[F[_], O, R](step: Eval[F, O, R])
        extends View[F, O, R, R](step) {
      def next(r: Result[R]): FreeC[F, O, R] = r.asFreeC[F]
    }

    private[fs2] def apply[F[_], O, R](free: FreeC[F, O, R]): ViewL[F, O, R] = mk(free)

    @tailrec
    private def mk[F[_], O, Z](free: FreeC[F, O, Z]): ViewL[F, O, Z] =
      free match {
        case e: Eval[F, O, Z] => new EvalView[F, O, Z](e)
        case b: FreeC.Bind[F, O, y, Z] =>
          b.step match {
            case Result(r) => mk(b.cont(r))
            case e: FreeC.Eval[F, O, y] =>
              new ViewL.View[F, O, y, Z](e) {
                def next(r: Result[y]): FreeC[F, O, Z] = b.cont(r)
              }
            case bb: FreeC.Bind[F, O, x, _] =>
              val nb = new Bind[F, O, x, Z](bb.step) {
                private[this] val bdel: Bind[F, O, y, Z] = b.delegate
                def cont(zr: Result[x]): FreeC[F, O, Z] =
                  new Bind[F, O, y, Z](bb.cont(zr)) {
                    override val delegate: Bind[F, O, y, Z] = bdel
                    def cont(yr: Result[y]): FreeC[F, O, Z] = delegate.cont(yr)
                  }
              }
              mk(nb)
          }
        case r @ Result.Pure(_)           => r
        case r @ Result.Fail(_)           => r
        case r @ Result.Interrupted(_, _) => r
      }

  }

  def bracketCase[F[_], O, A, B](
      acquire: FreeC[F, O, A],
      use: A => FreeC[F, O, B],
      release: (A, ExitCase[Throwable]) => FreeC[F, O, Unit]
  ): FreeC[F, O, B] =
    acquire.flatMap { a =>
      val used =
        try use(a)
        catch { case NonFatal(t) => FreeC.Result.Fail[F](t) }
      used.transformWith { result =>
        release(a, result.asExitCase).transformWith {
          case Result.Fail(t2) =>
            result match {
              case Result.Fail(tres) => Result.Fail(CompositeFailure(tres, t2))
              case result            => result.asFreeC[F]
            }
          case _ => result.asFreeC[F]
        }
      }
    }
}
