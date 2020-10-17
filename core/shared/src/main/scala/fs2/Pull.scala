/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package fs2

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

import cats.{Eval => _, _}
import cats.effect._
import cats.syntax.all._

import fs2.internal._

import Pull._

/** A `p: Pull[F,O,R]` reads values from one or more streams, returns a
  * result of type `R`, and produces a `Stream[F,O]` when calling `p.stream`.
  *
  * Any resources acquired by `p` are freed following the call to `stream`.
  *
  * Laws:
  *
  * `Pull` forms a monad in `R` with `pure` and `flatMap`:
  *   - `pure >=> f == f`
  *   - `f >=> pure == f`
  *   - `(f >=> g) >=> h == f >=> (g >=> h)`
  * where `f >=> g` is defined as `a => a flatMap f flatMap g`
  *
  * `raiseError` is caught by `handleErrorWith`:
  *   - `handleErrorWith(raiseError(e))(f) == f(e)`
  */
sealed abstract class Pull[+F[_], +O, +R] {

  /** Alias for `_.map(_ => o2)`. */
  def as[R2](r2: R2): Pull[F, O, R2] = map(_ => r2)

  private[Pull] def asHandler(e: Throwable): Pull[F, O, R] =
    ViewL(this) match {
      case Result.Succeeded(_) => Result.Fail(e)
      case Result.Fail(e2)     => Result.Fail(CompositeFailure(e2, e))
      case Result.Interrupted(ctx, err) =>
        Result.Interrupted(ctx, err.map(t => CompositeFailure(e, t)).orElse(Some(e)))
      case v @ ViewL.View(_) => v.next(Result.Fail(e))
    }

  /** Returns a pull with the result wrapped in `Right`, or an error wrapped in `Left` if the pull has failed. */
  def attempt: Pull[F, O, Either[Throwable, R]] =
    map(r => Right(r)).handleErrorWith(t => Result.Succeeded(Left(t)))

  /** Interpret this `Pull` to produce a `Stream`, introducing a scope.
    *
    * May only be called on pulls which return a `Unit` result type. Use `p.void.stream` to explicitly
    * ignore the result type of the pull.
    */
  def stream(implicit ev: R <:< Unit): Stream[F, O] = {
    val _ = ev
    new Stream(Pull.scope(this.asInstanceOf[Pull[F, O, Unit]]))
  }

  /** Interpret this `Pull` to produce a `Stream` without introducing a scope.
    *
    * Only use this if you know a scope is not needed. Scope introduction is generally harmless and the risk
    * of not introducing a scope is a memory leak in streams that otherwise would execute in constant memory.
    *
    * May only be called on pulls which return a `Unit` result type. Use `p.void.stream` to explicitly
    * ignore the result type of the pull.
    */
  def streamNoScope(implicit ev: R <:< Unit): Stream[F, O] = {
    val _ = ev
    new Stream(this.asInstanceOf[Pull[F, O, Unit]])
  }

  /** Applies the resource of this pull to `f` and returns the result. */
  def flatMap[F2[x] >: F[x], O2 >: O, R2](f: R => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    new Bind[F2, O2, R, R2](this) {
      def cont(e: Result[R]): Pull[F2, O2, R2] =
        e match {
          case Result.Succeeded(r) =>
            try f(r)
            catch { case NonFatal(e) => Pull.Result.Fail(e) }
          case res @ Result.Interrupted(_, _) => res
          case res @ Result.Fail(_)           => res
        }
    }

  /** Alias for `flatMap(_ => p2)`. */
  def >>[F2[x] >: F[x], O2 >: O, R2](p2: => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    new Bind[F2, O2, R, R2](this) {
      def cont(r: Result[R]): Pull[F2, O2, R2] =
        r match {
          case _: Result.Succeeded[_] => p2
          case r: Result.Interrupted  => r
          case r: Result.Fail         => r
        }
    }

  /** Lifts this pull to the specified effect type. */
  def covary[F2[x] >: F[x]]: Pull[F2, O, R] = this

  /** Lifts this pull to the specified effect type, output type, and resource type. */
  def covaryAll[F2[x] >: F[x], O2 >: O, R2 >: R]: Pull[F2, O2, R2] = this

  /** Lifts this pull to the specified output type. */
  def covaryOutput[O2 >: O]: Pull[F, O2, R] = this

  /** Lifts this pull to the specified resource type. */
  def covaryResource[R2 >: R]: Pull[F, O, R2] = this

  /** Applies the resource of this pull to `f` and returns the result in a new `Pull`. */
  def map[R2](f: R => R2): Pull[F, O, R2] =
    new Bind[F, O, R, R2](this) {
      def cont(r: Result[R]) = r.map(f)
    }

  /** Applies the outputs of this pull to `f` and returns the result in a new `Pull`. */
  def mapOutput[O2](f: O => O2): Pull[F, O2, R]

  /** Run `p2` after `this`, regardless of errors during `this`, then reraise any errors encountered during `this`. */
  def onComplete[F2[x] >: F[x], O2 >: O, R2 >: R](p2: => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    handleErrorWith(e => p2 >> Result.Fail(e)) >> p2

  /** If `this` terminates with `Pull.raiseError(e)`, invoke `h(e)`. */
  def handleErrorWith[F2[x] >: F[x], O2 >: O, R2 >: R](
      h: Throwable => Pull[F2, O2, R2]
  ): Pull[F2, O2, R2] =
    new Bind[F2, O2, R2, R2](this) {
      def cont(e: Result[R2]): Pull[F2, O2, R2] =
        e match {
          case Result.Fail(e) =>
            try h(e)
            catch { case NonFatal(e) => Result.Fail(e) }
          case other => other
        }
    }

  private[Pull] def transformWith[F2[x] >: F[x], O2 >: O, R2](
      f: Result[R] => Pull[F2, O2, R2]
  ): Pull[F2, O2, R2] =
    new Bind[F2, O2, R, R2](this) {
      def cont(r: Result[R]): Pull[F2, O2, R2] =
        try f(r)
        catch { case NonFatal(e) => Result.Fail(e) }
    }

  /** Discards the result type of this pull. */
  def void: Pull[F, O, Unit] = as(())
}

object Pull extends PullLowPriority {

  private[fs2] def acquire[F[_], R](
      resource: F[R],
      release: (R, Resource.ExitCase) => F[Unit]
  ): Pull[F, INothing, R] = Acquire(Left(resource), release)

  private[fs2] def acquireCancelable[F[_], R](
      resource: Poll[F] => F[R],
      release: (R, Resource.ExitCase) => F[Unit]
  )(implicit F: MonadCancel[F, Throwable]): Pull[F, INothing, R] =
    Acquire(Right((resource, F)), release)

  /** Like [[eval]] but if the effectful value fails, the exception is returned in a `Left`
    * instead of failing the pull.
    */
  def attemptEval[F[_], R](fr: F[R]): Pull[F, INothing, Either[Throwable, R]] =
    Eval[F, R](fr)
      .map(r => Right(r): Either[Throwable, R])
      .handleErrorWith(t => Result.Succeeded[Either[Throwable, R]](Left(t)))

  def bracketCase[F[_], O, A, B](
      acquire: Pull[F, O, A],
      use: A => Pull[F, O, B],
      release: (A, Resource.ExitCase) => Pull[F, O, Unit]
  ): Pull[F, O, B] =
    acquire.flatMap { a =>
      val used =
        try use(a)
        catch { case NonFatal(t) => Pull.Result.Fail(t) }
      used.transformWith { result =>
        val exitCase: Resource.ExitCase = result match {
          case Result.Succeeded(_)      => Resource.ExitCase.Succeeded
          case Result.Fail(err)         => Resource.ExitCase.Errored(err)
          case Result.Interrupted(_, _) => Resource.ExitCase.Canceled
        }

        release(a, exitCase).transformWith {
          case Result.Fail(t2) =>
            result match {
              case Result.Fail(tres) => Result.Fail(CompositeFailure(tres, t2))
              case result            => result
            }
          case _ => result
        }
      }
    }

  /** The completed `Pull`. Reads and outputs nothing. */
  val done: Pull[Pure, INothing, Unit] =
    Result.unit

  /** Evaluates the supplied effectful value and returns the result as the resource of the returned pull. */
  def eval[F[_], R](fr: F[R]): Pull[F, INothing, R] =
    Eval[F, R](fr)

  /** Extends the scope of the currently open resources to the specified stream, preventing them
    * from being finalized until after `s` completes execution, even if the returned pull is converted
    * to a stream, compiled, and evaluated before `s` is compiled and evaluated.
    */
  def extendScopeTo[F[_], O](
      s: Stream[F, O]
  )(implicit F: MonadError[F, Throwable]): Pull[F, INothing, Stream[F, O]] =
    for {
      scope <- Pull.getScope[F]
      lease <- Pull.eval(scope.leaseOrError)
    } yield s.onFinalize(lease.cancel.redeemWith(F.raiseError(_), _ => F.unit))

  /** Repeatedly uses the output of the pull as input for the next step of the pull.
    * Halts when a step terminates with `None` or `Pull.raiseError`.
    */
  def loop[F[_], O, R](f: R => Pull[F, O, Option[R]]): R => Pull[F, O, Option[R]] =
    r => f(r).flatMap(_.map(loop(f)).getOrElse(Pull.pure(None)))

  /** Outputs a single value. */
  def output1[F[x] >: Pure[x], O](o: O): Pull[F, O, Unit] = Output(Chunk.singleton(o))

  /** Outputs a chunk of values. */
  def output[F[x] >: Pure[x], O](os: Chunk[O]): Pull[F, O, Unit] =
    if (os.isEmpty) Pull.done else Output[O](os)

  /** Pull that outputs nothing and has result of `r`. */
  def pure[F[x] >: Pure[x], R](r: R): Pull[F, INothing, R] =
    Result.Succeeded(r)

  /** Reads and outputs nothing, and fails with the given error.
    *
    * The `F` type must be explicitly provided (e.g., via `raiseError[IO]` or `raiseError[Fallible]`).
    */
  def raiseError[F[_]: RaiseThrowable](err: Throwable): Pull[F, INothing, INothing] =
    Result.Fail(err)

  private[fs2] def fail[F[_]](err: Throwable): Pull[F, INothing, INothing] =
    Result.Fail(err)

  final class PartiallyAppliedFromEither[F[_]] {
    def apply[A](either: Either[Throwable, A])(implicit ev: RaiseThrowable[F]): Pull[F, A, Unit] =
      either.fold(raiseError[F], output1)
  }

  /** Lifts an Either[Throwable, A] to an effectful Pull[F, A, Unit].
    *
    * @example {{{
    * scala> import cats.effect.SyncIO, scala.util.Try
    * scala> Pull.fromEither[SyncIO](Right(42)).stream.compile.toList.unsafeRunSync()
    * res0: List[Int] = List(42)
    * scala> Try(Pull.fromEither[SyncIO](Left(new RuntimeException)).stream.compile.toList.unsafeRunSync())
    * res1: Try[List[INothing]] = Failure(java.lang.RuntimeException)
    * }}}
    */
  def fromEither[F[x]] = new PartiallyAppliedFromEither[F]

  /** Gets the current scope, allowing manual leasing or interruption.
    * This is a low-level method and generally should not be used by user code.
    */
  def getScope[F[_]]: Pull[F, INothing, Scope[F]] = GetScope[F]()

  /** Returns a pull that evaluates the supplied by-name each time the pull is used,
    * allowing use of a mutable value in pull computations.
    */
  def suspend[F[x] >: Pure[x], O, R](p: => Pull[F, O, R]): Pull[F, O, R] =
    new Bind[F, O, Unit, R](Result.unit) {
      def cont(r: Result[Unit]): Pull[F, O, R] = p
    }

  /** `Sync` instance for `Pull`. */
  implicit def syncInstance[F[_]: Sync, O]: Sync[Pull[F, O, *]] =
    new PullSyncInstance[F, O]

  /** `FunctionK` instance for `F ~> Pull[F, INothing, *]`
    *
    * @example {{{
    * scala> import cats.Id
    * scala> Pull.functionKInstance[Id](42).flatMap(Pull.output1).stream.compile.toList
    * res0: cats.Id[List[Int]] = List(42)
    * }}}
    */
  implicit def functionKInstance[F[_]]: F ~> Pull[F, INothing, *] =
    new (F ~> Pull[F, INothing, *]) {
      def apply[X](fx: F[X]) = Pull.eval(fx)
    }

  /* Implementation notes:
   *
   * A Pull can be one of the following:
   *  - A Result - the end result of pulling. This may have ended in:
   *    - Succeeded with a result of type R.
   *    - Failed with an exception
   *    - Interrupted from another thread with a known `scopeId`
   *
   *  - A Bind, that binds a first computation(another Pull) with a method to _continue_
   *    the computation from the result of the first one `step`.
   *
   *  - A single Action, which can be one of following:
   *
   *    - Eval (or lift) an effectful operation of type `F[R]`
   *    - Output some values of type O.
   *    - Acquire a new resource and add its cleanup to the current scope.
   *    - Open, Close, or Access to the resource scope.
   *    - side-Step or fork to a different computation
   */

  /** A Result, or terminal, indicates how a pull or Free evaluation ended.
    * A Pull may have succeeded with a result, failed with an exception,
    * or interrupted from another concurrent pull.
    */
  private sealed abstract class Result[+R]
      extends Pull[Pure, INothing, R]
      with ViewL[Pure, INothing, R] {
    override def mapOutput[P](f: INothing => P): Pull[Pure, INothing, R] = this
  }

  private object Result {
    val unit: Result[Unit] = Result.Succeeded(())

    def fromEither[R](either: Either[Throwable, R]): Result[R] =
      either.fold(Result.Fail(_), Result.Succeeded(_))

    final case class Succeeded[+R](r: R) extends Result[R] {
      override def map[R2](f: R => R2): Result[R2] =
        try Succeeded(f(r))
        catch { case NonFatal(err) => Fail(err) }
    }

    final case class Fail(error: Throwable) extends Result[INothing] {
      override def map[R](f: INothing => R): Result[R] = this
    }

    /** Signals that Pull evaluation was interrupted.
      *
      * @param context Any user specific context that needs to be captured during interruption
      *                for eventual resume of the operation.
      *
      * @param deferredError Any errors, accumulated during resume of the interruption.
      *                      Instead throwing errors immediately during interruption,
      *                      signalling of the errors may be deferred until the Interruption resumes.
      */
    final case class Interrupted(context: Token, deferredError: Option[Throwable])
        extends Result[INothing] {
      override def map[R](f: INothing => R): Result[R] = this
    }
  }

  private abstract class Bind[+F[_], +O, X, +R](val step: Pull[F, O, X]) extends Pull[F, O, R] {
    def cont(r: Result[X]): Pull[F, O, R]
    def delegate: Bind[F, O, X, R] = this

    override def mapOutput[P](f: O => P): Pull[F, P, R] =
      suspend {
        ViewL(this) match {
          case v: ViewL.View[F, O, x, R] =>
            new Bind[F, P, x, R](v.step.mapOutput(f)) {
              def cont(e: Result[x]) = v.next(e).mapOutput(f)
            }
          case r: Result[R] => r
        }
      }
  }

  /** Unrolled view of a `Pull` structure. may be `Result` or `EvalBind`
    */
  private sealed trait ViewL[+F[_], +O, +R]

  private object ViewL {

    /** unrolled view of Pull `bind` structure * */
    private[Pull] sealed abstract case class View[+F[_], +O, X, +R](step: Action[F, O, X])
        extends ViewL[F, O, R] {
      def next(r: Result[X]): Pull[F, O, R]
    }

    final class EvalView[+F[_], +O, R](step: Action[F, O, R]) extends View[F, O, R, R](step) {
      def next(r: Result[R]): Pull[F, O, R] = r
    }

    def apply[F[_], O, R](free: Pull[F, O, R]): ViewL[F, O, R] = mk(free)

    @tailrec
    private def mk[F[_], O, Z](free: Pull[F, O, Z]): ViewL[F, O, Z] =
      free match {
        case r: Result[Z]       => r
        case e: Action[F, O, Z] => new EvalView[F, O, Z](e)
        case b: Bind[F, O, y, Z] =>
          b.step match {
            case r: Result[_] =>
              val ry: Result[y] = r.asInstanceOf[Result[y]]
              mk(b.cont(ry))
            case e: Action[F, O, y2] =>
              new ViewL.View[F, O, y2, Z](e) {
                def next(r: Result[y2]): Pull[F, O, Z] = b.cont(r.asInstanceOf[Result[y]])
              }
            case bb: Bind[F, O, x, _] =>
              val nb = new Bind[F, O, x, Z](bb.step) {
                private[this] val bdel: Bind[F, O, y, Z] = b.delegate
                def cont(zr: Result[x]): Pull[F, O, Z] =
                  new Bind[F, O, y, Z](bb.cont(zr).asInstanceOf[Pull[F, O, y]]) {
                    override val delegate: Bind[F, O, y, Z] = bdel
                    def cont(yr: Result[y]): Pull[F, O, Z] = delegate.cont(yr)
                  }
              }
              mk(nb)
          }
      }
  }

  /* An Action is an atomic instruction that can perform effects in `F`
   * to generate by-product outputs of type `O`.
   *
   * Each operation also generates an output of type `R` that is used
   * as control information for the rest of the interpretation or compilation.
   */
  private abstract class Action[+F[_], +O, +R] extends Pull[F, O, R]

  private final case class Output[+O](values: Chunk[O]) extends Action[Pure, O, Unit] {
    override def mapOutput[P](f: O => P): Pull[Pure, P, Unit] =
      Pull.suspend {
        try Output(values.map(f))
        catch { case NonFatal(t) => Result.Fail(t) }
      }
  }

  /* A translation point, that wraps an inner stream written in another effect
   */
  private final case class Translate[G[_], F[_], +O](
      stream: Pull[G, O, Unit],
      fk: G ~> F
  ) extends Action[F, O, Unit] {
    override def mapOutput[O2](f: O => O2): Pull[F, O2, Unit] =
      Translate(stream.mapOutput(f), fk)
  }

  /** Steps through the stream, providing either `uncons` or `stepLeg`.
    * Yields to head in form of chunk, then id of the scope that was active after step evaluated and tail of the `stream`.
    *
    * @param stream             Stream to step
    * @param scopeId            If scope has to be changed before this step is evaluated, id of the scope must be supplied
    */
  private final case class Step[+F[_], X](stream: Pull[F, X, Unit], scope: Option[Token])
      extends Action[Pure, INothing, Option[(Chunk[X], Token, Pull[F, X, Unit])]] {
    override def mapOutput[P](f: INothing => P): Step[F, X] = this
  }

  /* The `AlgEffect` trait is for operations on the `F` effect that create no `O` output.
   * They are related to resources and scopes. */
  private sealed abstract class AlgEffect[+F[_], R] extends Action[F, INothing, R] {
    final def mapOutput[P](f: INothing => P): Pull[F, P, R] = this
  }

  private final case class Eval[+F[_], R](value: F[R]) extends AlgEffect[F, R]

  private final case class Acquire[F[_], R](
      resource: Either[F[R], (Poll[F] => F[R], MonadCancel[F, Throwable])],
      release: (R, Resource.ExitCase) => F[Unit]
  ) extends AlgEffect[F, R]

  private final case class OpenScope(useInterruption: Boolean) extends AlgEffect[Pure, Token]

  // `InterruptedScope` contains id of the scope currently being interrupted
  // together with any errors accumulated during interruption process
  private final case class CloseScope(
      scopeId: Token,
      interruption: Option[Result.Interrupted],
      exitCase: Resource.ExitCase
  ) extends AlgEffect[Pure, Unit]

  private final case class GetScope[F[_]]() extends AlgEffect[Pure, CompileScope[F]]
  private[fs2] def getScopeInternal[F[_]]: Pull[Pure, INothing, CompileScope[F]] = GetScope[F]()

  private[fs2] def stepLeg[F[_], O](
      leg: Stream.StepLeg[F, O]
  ): Pull[F, Nothing, Option[Stream.StepLeg[F, O]]] =
    Step[F, O](leg.next, Some(leg.scopeId)).map {
      _.map { case (h, id, t) =>
        new Stream.StepLeg[F, O](h, id, t.asInstanceOf[Pull[F, O, Unit]])
      }
    }

  /** Wraps supplied pull in new scope, that will be opened before this pull is evaluated
    * and closed once this pull either finishes its evaluation or when it fails.
    */
  private[fs2] def scope[F[_], O](s: Pull[F, O, Unit]): Pull[F, O, Unit] =
    scope0(s, false)

  /** Like `scope` but allows this scope to be interrupted.
    * Note that this may fail with `Interrupted` when interruption occurred
    */
  private[fs2] def interruptScope[F[_], O](
      s: Pull[F, O, Unit]
  ): Pull[F, O, Unit] = scope0(s, true)

  private def scope0[F[_], O](
      s: Pull[F, O, Unit],
      interruptible: Boolean
  ): Pull[F, O, Unit] =
    OpenScope(interruptible).flatMap { scopeId =>
      s.transformWith {
        case Result.Succeeded(_) => CloseScope(scopeId, None, Resource.ExitCase.Succeeded)
        case interrupted @ Result.Interrupted(_, _) =>
          CloseScope(scopeId, Some(interrupted), Resource.ExitCase.Canceled)
        case Result.Fail(err) =>
          CloseScope(scopeId, None, Resource.ExitCase.Errored(err)).transformWith {
            case Result.Succeeded(_) => Result.Fail(err)
            case Result.Fail(err0)   => Result.Fail(CompositeFailure(err, err0, Nil))
            case Result.Interrupted(interruptedScopeId, _) =>
              sys.error(
                s"Impossible, cannot interrupt when closing failed scope: $scopeId, $interruptedScopeId, $err"
              )
          }
      }
    }

  private[fs2] def uncons[F[_], X, O](
      s: Pull[F, O, Unit]
  ): Pull[F, X, Option[(Chunk[O], Pull[F, O, Unit])]] =
    Step(s, None).map(_.map { case (h, _, t) => (h, t.asInstanceOf[Pull[F, O, Unit]]) })

  /* Left-folds the output of a stream.
   *
   * Interruption of the stream is tightly coupled between Pull and CompileScope.
   * Reason for this is unlike interruption of `F` type (e.g. IO) we need to find
   * recovery point where stream evaluation has to continue in Stream algebra.
   *
   * As such the `Token` is passed to Pull.Result.Interrupted as glue between Pull that allows pass-along
   * the information to correctly compute recovery point after interruption was signalled via `CompileScope`.
   *
   * This token indicates scope of the computation where interruption actually happened.
   * This is used to precisely find most relevant interruption scope where interruption shall be resumed
   * for normal continuation of the stream evaluation.
   *
   * Interpreter uses this to find any parents of this scope that has to be interrupted, and guards the
   * interruption so it won't propagate to scope that shall not be anymore interrupted.
   */
  private[fs2] def compile[F[_], O, B](
      stream: Pull[F, O, Unit],
      initScope: CompileScope[F],
      extendLastTopLevelScope: Boolean,
      init: B
  )(g: (B, Chunk[O]) => B)(implicit
      F: MonadError[F, Throwable]
  ): F[B] = {

    sealed trait R[+G[_], +X]
    case class Done(scope: CompileScope[F]) extends R[Pure, INothing]
    case class Out[+G[_], +X](head: Chunk[X], scope: CompileScope[F], tail: Pull[G, X, Unit])
        extends R[G, X]
    case class Interrupted(scopeId: Token, err: Option[Throwable]) extends R[Pure, INothing]

    def go[G[_], X](
        scope: CompileScope[F],
        extendedTopLevelScope: Option[CompileScope[F]],
        translation: G ~> F,
        stream: Pull[G, X, Unit]
    ): F[R[G, X]] =
      ViewL(stream) match {
        case _: Pull.Result.Succeeded[Unit] =>
          F.pure(Done(scope))

        case failed: Pull.Result.Fail =>
          F.raiseError(failed.error)

        case interrupted: Pull.Result.Interrupted =>
          F.pure(Interrupted(interrupted.context, interrupted.deferredError))

        case view: ViewL.View[G, X, y, Unit] =>
          def interruptGuard(scope: CompileScope[F])(next: => F[R[G, X]]): F[R[G, X]] =
            F.flatMap(scope.isInterrupted) {
              case None => next
              case Some(outcome) =>
                val result = outcome match {
                  case Outcome.Errored(err)       => Result.Fail(err)
                  case Outcome.Canceled()         => Result.Interrupted(scope.id, None)
                  case Outcome.Succeeded(scopeId) => Result.Interrupted(scopeId, None)
                }
                go(scope, extendedTopLevelScope, translation, view.next(result))
            }
          view.step match {
            case output: Output[_] =>
              interruptGuard(scope)(
                F.pure(Out(output.values, scope, view.next(Pull.Result.unit)))
              )

            case tst: Translate[h, g, x] =>
              val composed: h ~> F = translation.asInstanceOf[g ~> F].compose[h](tst.fk)
              val runInner: F[R[h, x]] =
                go[h, x](scope, extendedTopLevelScope, composed, tst.stream)

              F.map(runInner) {
                case out: Out[h, x]            => Out[g, x](out.head, out.scope, Translate(out.tail, tst.fk))
                case dd @ Done(_)              => dd
                case inter @ Interrupted(_, _) => inter
              }

            case uU: Step[f, y] =>
              val u: Step[G, y] = uU.asInstanceOf[Step[G, y]]
              // if scope was specified in step, try to find it, otherwise use the current scope.
              F.flatMap(u.scope.fold[F[Option[CompileScope[F]]]](F.pure(Some(scope))) { scopeId =>
                scope.findStepScope(scopeId)
              }) {
                case Some(stepScope) =>
                  val stepStream = u.stream.asInstanceOf[Pull[G, y, Unit]]
                  val runInner = go[G, y](stepScope, extendedTopLevelScope, translation, stepStream)
                  F.flatMap(F.attempt(runInner)) {
                    case Right(Done(scope)) =>
                      interruptGuard(scope) {
                        val result = Result.Succeeded(None)
                        go(scope, extendedTopLevelScope, translation, view.next(result))
                      }
                    case Right(out: Out[g, y]) =>
                      // if we originally swapped scopes we want to return the original
                      // scope back to the go as that is the scope that is expected to be here.
                      val nextScope = if (u.scope.isEmpty) out.scope else scope
                      val uncons = (out.head, out.scope.id, out.tail.asInstanceOf[Pull[f, y, Unit]])
                      //Option[(Chunk[y], Token, Pull[f, y, Unit])])
                      val result = Result.Succeeded(Some(uncons))
                      interruptGuard(nextScope) {
                        val next = view.next(result).asInstanceOf[Pull[g, X, Unit]]
                        go(nextScope, extendedTopLevelScope, translation, next)
                      }

                    case Right(Interrupted(scopeId, err)) =>
                      val cont = view.next(Result.Interrupted(scopeId, err))
                      go(scope, extendedTopLevelScope, translation, cont)

                    case Left(err) =>
                      go(scope, extendedTopLevelScope, translation, view.next(Result.Fail(err)))
                  }

                case None =>
                  F.raiseError(
                    new RuntimeException(
                      s"""|Scope lookup failure!
                          |
                          |This is typically caused by uncons-ing from two or more streams in the same Pull.
                          |To do this safely, use `s.pull.stepLeg` instead of `s.pull.uncons` or a variant
                          |thereof. See the implementation of `Stream#zipWith_` for an example.
                          |
                          |Scope id: ${scope.id}
                          |Step: ${u}""".stripMargin
                    )
                  )
              }

            case eval: Eval[G, r] =>
              F.flatMap(scope.interruptibleEval(translation(eval.value))) { eitherOutcome =>
                val result = eitherOutcome match {
                  case Right(r)                       => Result.Succeeded(r)
                  case Left(Outcome.Errored(err))     => Result.Fail(err)
                  case Left(Outcome.Canceled())       => Result.Interrupted(scope.id, None)
                  case Left(Outcome.Succeeded(token)) => Result.Interrupted(token, None)
                }
                go(scope, extendedTopLevelScope, translation, view.next(result))
              }

            case acquire: Acquire[G, r] =>
              interruptGuard(scope) {
                val onScope = scope.acquireResource[r](
                  p =>
                    acquire.resource match {
                      case Left(acq)        => translation(acq)
                      case Right((acq, mc)) => p(translation(mc.uncancelable(acq)))
                    },
                  (r: r, ec: Resource.ExitCase) => translation(acquire.release(r, ec))
                )

                F.flatMap(onScope) { outcome =>
                  val result = outcome match {
                    case Outcome.Succeeded(Right(r))      => Result.Succeeded(r)
                    case Outcome.Succeeded(Left(scopeId)) => Result.Interrupted(scopeId, None)
                    case Outcome.Canceled()               => Result.Interrupted(scope.id, None)
                    case Outcome.Errored(err)             => Result.Fail(err)
                  }
                  go(scope, extendedTopLevelScope, translation, view.next(result))
                }
              }

            case _: GetScope[_] =>
              val result = Result.Succeeded(scope.asInstanceOf[y])
              go(scope, extendedTopLevelScope, translation, view.next(result))

            case open: OpenScope =>
              interruptGuard(scope) {
                val maybeCloseExtendedScope: F[Boolean] =
                  // If we're opening a new top-level scope (aka, direct descendant of root),
                  // close the current extended top-level scope if it is defined.
                  if (scope.parent.isEmpty)
                    extendedTopLevelScope match {
                      case None    => false.pure[F]
                      case Some(s) => s.close(Resource.ExitCase.Succeeded).rethrow.as(true)
                    }
                  else F.pure(false)
                maybeCloseExtendedScope.flatMap { closedExtendedScope =>
                  val newExtendedScope = if (closedExtendedScope) None else extendedTopLevelScope
                  F.flatMap(scope.open(open.useInterruption)) {
                    case Left(err) =>
                      val result = Result.Fail(err)
                      go(scope, newExtendedScope, translation, view.next(result))
                    case Right(childScope) =>
                      val result = Result.Succeeded(childScope.id)
                      go(childScope, newExtendedScope, translation, view.next(result))
                  }
                }
              }

            case close: CloseScope =>
              def closeAndGo(toClose: CompileScope[F], ec: Resource.ExitCase) =
                F.flatMap(toClose.close(ec)) { r =>
                  F.flatMap(toClose.openAncestor) { ancestor =>
                    val res = close.interruption match {
                      case None => Result.fromEither(r)
                      case Some(Result.Interrupted(interruptedScopeId, err)) =>
                        def err1 = CompositeFailure.fromList(r.swap.toOption.toList ++ err.toList)
                        if (ancestor.findSelfOrAncestor(interruptedScopeId).isDefined)
                          // we still have scopes to interrupt, lets build interrupted tail
                          Result.Interrupted(interruptedScopeId, err1)
                        else
                          // interrupts scope was already interrupted, resume operation
                          err1 match {
                            case None      => Result.unit
                            case Some(err) => Result.Fail(err)
                          }
                    }
                    go(ancestor, extendedTopLevelScope, translation, view.next(res))
                  }
                }

              val scopeToClose: F[Option[CompileScope[F]]] = scope
                .findSelfOrAncestor(close.scopeId)
                .pure[F]
                .orElse(scope.findSelfOrChild(close.scopeId))
              F.flatMap(scopeToClose) {
                case Some(toClose) =>
                  if (toClose.parent.isEmpty)
                    // Impossible - don't close root scope as a result of a `CloseScope` call
                    go(scope, extendedTopLevelScope, translation, view.next(Result.unit))
                  else if (extendLastTopLevelScope && toClose.parent.flatMap(_.parent).isEmpty)
                    // Request to close the current top-level scope - if we're supposed to extend
                    // it instead, leave the scope open and pass it to the continuation
                    extendedTopLevelScope.traverse_(_.close(Resource.ExitCase.Succeeded).rethrow) *>
                      F.flatMap(toClose.openAncestor)(ancestor =>
                        go(ancestor, Some(toClose), translation, view.next(Result.unit))
                      )
                  else closeAndGo(toClose, close.exitCase)
                case None =>
                  // scope already closed, continue with current scope
                  val result = close.interruption.getOrElse(Result.unit)
                  go(scope, extendedTopLevelScope, translation, view.next(result))
              }
          }
      }

    val initFk: F ~> F = cats.arrow.FunctionK.id[F]

    def outerLoop(scope: CompileScope[F], accB: B, stream: Pull[F, O, Unit]): F[B] =
      F.flatMap(go[F, O](scope, None, initFk, stream)) {
        case Done(_) => F.pure(accB)
        case out: Out[f, o] =>
          try outerLoop(out.scope, g(accB, out.head), out.tail.asInstanceOf[Pull[f, O, Unit]])
          catch {
            case NonFatal(err) =>
              outerLoop(out.scope, accB, out.tail.asHandler(err).asInstanceOf[Pull[f, O, Unit]])
          }
        case Interrupted(_, None)      => F.pure(accB)
        case Interrupted(_, Some(err)) => F.raiseError(err)
      }

    outerLoop(initScope, init, stream)
  }

  private[fs2] def flatMapOutput[F[_], F2[x] >: F[x], O, O2](
      p: Pull[F, O, Unit],
      f: O => Pull[F2, O2, Unit]
  ): Pull[F2, O2, Unit] =
    uncons(p).flatMap {
      case None => Result.unit

      case Some((chunk, Pull.Result.Succeeded(_))) if chunk.size == 1 =>
        // nb: If tl is Pure, there's no need to propagate flatMap through the tail. Hence, we
        // check if hd has only a single element, and if so, process it directly instead of folding.
        // This allows recursive infinite streams of the form `def s: Stream[Pure,O] = Stream(o).flatMap { _ => s }`
        f(chunk(0))

      case Some((chunk, tail)) =>
        def go(idx: Int): Pull[F2, O2, Unit] =
          if (idx == chunk.size)
            flatMapOutput[F, F2, O, O2](tail, f)
          else
            f(chunk(idx)).transformWith {
              case Result.Succeeded(_) => go(idx + 1)
              case Result.Fail(err)    => Result.Fail(err)
              case interruption @ Result.Interrupted(_, _) =>
                flatMapOutput[F, F2, O, O2](interruptBoundary(tail, interruption), f)
            }

        go(0)
    }

  /** Inject interruption to the tail used in flatMap.
    * Assures that close of the scope is invoked if at the flatMap tail, otherwise switches evaluation to `interrupted` path
    *
    * @param stream             tail to inject interruption into
    * @param interruptedScope   scopeId to interrupt
    * @param interruptedError   Additional finalizer errors
    * @tparam F
    * @tparam O
    * @return
    */
  private[this] def interruptBoundary[F[_], O](
      stream: Pull[F, O, Unit],
      interruption: Result.Interrupted
  ): Pull[F, O, Unit] =
    ViewL(stream) match {
      case _: Pull.Result.Succeeded[Unit] =>
        interruption
      case failed: Pull.Result.Fail =>
        Result.Fail(
          CompositeFailure
            .fromList(interruption.deferredError.toList :+ failed.error)
            .getOrElse(failed.error)
        )
      case interrupted: Result.Interrupted => interrupted // impossible

      case view: ViewL.View[F, O, _, Unit] =>
        view.step match {
          case CloseScope(scopeId, _, _) =>
            // Inner scope is getting closed b/c a parent was interrupted
            CloseScope(scopeId, Some(interruption), Resource.ExitCase.Canceled)
              .transformWith(view.next)
          case _ =>
            // all other cases insert interruption cause
            view.next(interruption)
        }
    }

  private[fs2] def translate[F[_], G[_], O](
      stream: Pull[F, O, Unit],
      fK: F ~> G
  ): Pull[G, O, Unit] =
    Translate(stream, fK)

  /** Provides syntax for pure pulls based on `cats.Id`. */
  implicit final class IdOps[O](private val self: Pull[Id, O, Unit]) extends AnyVal {
    private def idToApplicative[F[_]: Applicative]: Id ~> F =
      new (Id ~> F) { def apply[A](a: Id[A]) = a.pure[F] }

    def covaryId[F[_]: Applicative]: Pull[F, O, Unit] = Pull.translate(self, idToApplicative[F])
  }
}

private[fs2] trait PullLowPriority {
  implicit def monadErrorInstance[F[_], O]: MonadError[Pull[F, O, *], Throwable] =
    new PullMonadErrorInstance[F, O]
}

private[fs2] class PullMonadErrorInstance[F[_], O] extends MonadError[Pull[F, O, *], Throwable] {
  def pure[A](a: A): Pull[F, O, A] = Pull.pure(a)
  def flatMap[A, B](p: Pull[F, O, A])(f: A => Pull[F, O, B]): Pull[F, O, B] =
    p.flatMap(f)
  override def tailRecM[A, B](a: A)(f: A => Pull[F, O, Either[A, B]]): Pull[F, O, B] =
    f(a).flatMap {
      case Left(a)  => tailRecM(a)(f)
      case Right(b) => Pull.pure(b)
    }
  def raiseError[A](e: Throwable) = Pull.fail(e)
  def handleErrorWith[A](fa: Pull[F, O, A])(h: Throwable => Pull[F, O, A]) =
    fa.handleErrorWith(h)
}

private[fs2] class PullSyncInstance[F[_], O](implicit F: Sync[F])
    extends PullMonadErrorInstance[F, O]
    with Sync[Pull[F, O, *]] {
  def monotonic: Pull[F, O, FiniteDuration] = Pull.eval(F.monotonic)
  def realTime: Pull[F, O, FiniteDuration] = Pull.eval(F.realTime)
  def suspend[A](hint: Sync.Type)(thunk: => A): Pull[F, O, A] = Pull.eval(F.suspend(hint)(thunk))
}
