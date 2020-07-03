package fs2

import scala.annotation.tailrec
import scala.util.control.NonFatal

import cats.{Eval => _, _}
import cats.arrow.FunctionK
import cats.effect._
import cats.implicits._

import fs2.internal._

import Pull._

/**
  * A `p: Pull[F,O,R]` reads values from one or more streams, returns a
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
sealed abstract class Pull[+F[_], +O, +R]  {

  /** Alias for `_.map(_ => o2)`. */
  def as[R2](r2: R2): Pull[F, O, R2] = map(_ => r2)

  private[Pull] def asHandler(e: Throwable): Pull[F, O, R] =
    ViewL(this) match {
      case Result.Succeeded(_)  => Result.Fail(e)
      case Result.Fail(e2) => Result.Fail(CompositeFailure(e2, e))
      case Result.Interrupted(ctx, err) =>
        Result.Interrupted(ctx, err.map(t => CompositeFailure(e, t)).orElse(Some(e)))
      case v @ ViewL.View(_) => v.next(Result.Fail(e))
    }

  /** Returns a pull with the result wrapped in `Right`, or an error wrapped in `Left` if the pull has failed. */
  def attempt: Pull[F, O, Either[Throwable, R]] =
    map(r => Right(r)).handleErrorWith(t => Result.Succeeded(Left(t)))

  /**
    * Interpret this `Pull` to produce a `Stream`, introducing a scope.
    *
    * May only be called on pulls which return a `Unit` result type. Use `p.void.stream` to explicitly
    * ignore the result type of the pull.
    */
  def stream(implicit ev: R <:< Unit): Stream[F, O] = {
    val _ = ev
    new Stream(Pull.scope(this.asInstanceOf[Pull[F, O, Unit]]))
  }

  /**
    * Interpret this `Pull` to produce a `Stream` without introducing a scope.
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
          case _: Result.Succeeded[_]     => p2
          case r: Result.Interrupted => r
          case r: Result.Fail        => r
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
      release: (R, ExitCase[Throwable]) => F[Unit]
  ): Pull[F, INothing, R] = Acquire(resource, release)

  /**
    * Like [[eval]] but if the effectful value fails, the exception is returned in a `Left`
    * instead of failing the pull.
    */
  def attemptEval[F[_], R](fr: F[R]): Pull[F, INothing, Either[Throwable, R]] =
      Eval[F, R](fr)
        .map(r => Right(r): Either[Throwable, R])
        .handleErrorWith(t => Result.Succeeded[Either[Throwable, R]](Left(t)))

  def bracketCase[F[_], O, A, B](
      acquire: Pull[F, O, A],
      use: A => Pull[F, O, B],
      release: (A, ExitCase[Throwable]) => Pull[F, O, Unit]
  ): Pull[F, O, B] =
    acquire.flatMap { a =>
      val used =
        try use(a)
        catch { case NonFatal(t) => Pull.Result.Fail(t) }
      used.transformWith { result =>
        val exitCase: ExitCase[Throwable] = result match {
          case Result.Succeeded(_)           => ExitCase.Completed
          case Result.Fail(err)         => ExitCase.Error(err)
          case Result.Interrupted(_, _) => ExitCase.Canceled
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

  /**
    * Repeatedly uses the output of the pull as input for the next step of the pull.
    * Halts when a step terminates with `None` or `Pull.raiseError`.
    */
  def loop[F[_], O, R](using: R => Pull[F, O, Option[R]]): R => Pull[F, O, Option[R]] =
    r => using(r).flatMap(_.map(loop(using)).getOrElse(Pull.pure(None)))

  /** Outputs a single value. */
  def output1[F[x] >: Pure[x], O](o: O): Pull[F, O, Unit] = Output(Chunk.singleton(o))

  /** Outputs a chunk of values. */
  def output[F[x] >: Pure[x], O](os: Chunk[O]): Pull[F, O, Unit] =
    if (os.isEmpty) Pull.done else Output[O](os)

  /** Pull that outputs nothing and has result of `r`. */
  def pure[F[x] >: Pure[x], R](r: R): Pull[F, INothing, R] =
    Result.Succeeded(r)

  /**
    * Reads and outputs nothing, and fails with the given error.
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

  /**
    * Lifts an Either[Throwable, A] to an effectful Pull[F, A, Unit].
    *
    * @example {{{
    * scala> import cats.effect.IO, scala.util.Try
    * scala> Pull.fromEither[IO](Right(42)).stream.compile.toList.unsafeRunSync()
    * res0: List[Int] = List(42)
    * scala> Try(Pull.fromEither[IO](Left(new RuntimeException)).stream.compile.toList.unsafeRunSync())
    * res1: Try[List[INothing]] = Failure(java.lang.RuntimeException)
    * }}}
    */
  def fromEither[F[x]] = new PartiallyAppliedFromEither[F]

  /**
    * Returns a pull that evaluates the supplied by-name each time the pull is used,
    * allowing use of a mutable value in pull computations.
    */
  def suspend[F[x] >: Pure[x], O, R](p: => Pull[F, O, R]): Pull[F, O, R] =
    new Bind[F, O, Unit, R](Result.unit) {
      def cont(r: Result[Unit]): Pull[F, O, R] = p
    }

  /** `Sync` instance for `Pull`. */
  implicit def syncInstance[F[_], O](implicit
      ev: ApplicativeError[F, Throwable]
  ): Sync[Pull[F, O, *]] = {
    val _ = ev
    new PullSyncInstance[F, O]
  }

  /**
    * `FunctionK` instance for `F ~> Pull[F, INothing, *]`
    *
    * @example {{{
    * scala> import cats.Id
    * scala> Pull.functionKInstance[Id](42).flatMap(Pull.output1).stream.compile.toList
    * res0: cats.Id[List[Int]] = List(42)
    * }}}
    */
  implicit def functionKInstance[F[_]]: F ~> Pull[F, INothing, *] =
    FunctionK.lift[F, Pull[F, INothing, *]](Pull.eval)

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

    /**
      * Signals that Pull evaluation was interrupted.
      *
      * @param context Any user specific context that needs to be captured during interruption
      *                for eventual resume of the operation.
      *
      * @param deferredError Any errors, accumulated during resume of the interruption.
      *                      Instead throwing errors immediately during interruption,
      *                      signalling of the errors may be deferred until the Interruption resumes.
      */
    final case class Interrupted(context: Token, deferredError: Option[Throwable]) extends Result[INothing] {
      override def map[R](f: INothing => R): Result[R] = this
    }
  }

  private abstract class Bind[F[_], O, X, R](val step: Pull[F, O, X]) extends Pull[F, O, R] {
    def cont(r: Result[X]): Pull[F, O, R]
    def delegate: Bind[F, O, X, R] = this

    override def mapOutput[P](f: O => P): Pull[F, P, R] =
      suspend {
        ViewL(this) match {
          case v: ViewL.View[F, O, x, R] =>
            new Bind[F, P, x, R](v.step.mapOutput(f)) {
              def cont(e: Result[x]) = v.next(e).mapOutput(f)
            }
          case r: Result[_] => r
        }
      }
  }

  /**
    * Unrolled view of a `Pull` structure. may be `Result` or `EvalBind`
    */
  private sealed trait ViewL[+F[_], +O, +R]

  private object ViewL {

    /** unrolled view of Pull `bind` structure * */
    private[Pull] sealed abstract case class View[+F[_], O, X, R](step: Action[F, O, X]) extends ViewL[F, O, R] {
      def next(r: Result[X]): Pull[F, O, R]
    }

    final class EvalView[+F[_], O, R](step: Action[F, O, R])
        extends View[F, O, R, R](step) {
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
            case r: Result[_] => mk(b.cont(r))
            case e: Action[F, O, y] =>
              new ViewL.View[F, O, y, Z](e) {
                def next(r: Result[y]): Pull[F, O, Z] = b.cont(r)
              }
            case bb: Bind[F, O, x, _] =>
              val nb = new Bind[F, O, x, Z](bb.step) {
                private[this] val bdel: Bind[F, O, y, Z] = b.delegate
                def cont(zr: Result[x]): Pull[F, O, Z] =
                  new Bind[F, O, y, Z](bb.cont(zr)) {
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

  private final case class Output[O](values: Chunk[O]) extends Action[Pure, O, Unit] {
    override def mapOutput[P](f: O => P): Pull[Pure, P, Unit] =
      Pull.suspend {
        try Output(values.map(f))
        catch { case NonFatal(t) => Result.Fail(t) }
      }
  }

  /**
    * Steps through the stream, providing either `uncons` or `stepLeg`.
    * Yields to head in form of chunk, then id of the scope that was active after step evaluated and tail of the `stream`.
    *
    * @param stream             Stream to step
    * @param scopeId            If scope has to be changed before this step is evaluated, id of the scope must be supplied
    */
  private final case class Step[X](stream: Pull[Any, X, Unit], scope: Option[Token])
      extends Action[Pure, INothing, Option[(Chunk[X], Token, Pull[Any, X, Unit])]] {
    /* NOTE: The use of `Any` and `Pure` done to by-pass an error in Scala 2.12 type-checker,
     * that produces a crash when dealing with Higher-Kinded GADTs in which the F parameter appears
     * Inside one of the values of the case class.      */
    override def mapOutput[P](f: INothing => P): Step[X] = this
  }

  /* The `AlgEffect` trait is for operations on the `F` effect that create no `O` output.
   * They are related to resources and scopes. */
  private sealed abstract class AlgEffect[+F[_], R] extends Action[F, INothing, R] {
    final def mapOutput[P](f: INothing => P): Pull[F, P, R] = this
  }

  private final case class Eval[+F[_], R](value: F[R]) extends AlgEffect[F, R]

  private final case class Acquire[+F[_], R](
      resource: F[R],
      release: (R, ExitCase[Throwable]) => F[Unit]
  ) extends AlgEffect[F, R]
  // NOTE: The use of a separate `G` and `Pure` is done o by-pass a compiler-crash in Scala 2.12,
  // involving GADTs with a covariant Higher-Kinded parameter. */
  private final case class OpenScope[G[_]](interruptible: Option[Concurrent[G]])
      extends AlgEffect[Pure, Token]

  // `InterruptedScope` contains id of the scope currently being interrupted
  // together with any errors accumulated during interruption process
  private final case class CloseScope(
      scopeId: Token,
      interruption: Option[Result.Interrupted],
      exitCase: ExitCase[Throwable]
  ) extends AlgEffect[Pure, Unit]

  private final case class GetScope[F[_]]() extends AlgEffect[Pure, CompileScope[F]]
  private[fs2] def getScope[F[_]]: Pull[Pure, INothing, CompileScope[F]] = GetScope[F]()

  private[fs2] def stepLeg[F[_], O](leg: Stream.StepLeg[F, O]): Pull[F, Nothing, Option[Stream.StepLeg[F, O]]] =
    Step[O](leg.next, Some(leg.scopeId)).map {
      _.map {
        case (h, id, t) => new Stream.StepLeg[F, O](h, id, t.asInstanceOf[Pull[F, O, Unit]])
      }
    }

  /**
    * Wraps supplied pull in new scope, that will be opened before this pull is evaluated
    * and closed once this pull either finishes its evaluation or when it fails.
    */
  private[fs2] def scope[F[_], O](s: Pull[F, O, Unit]): Pull[F, O, Unit] =
    scope0(s, None)

  /**
    * Like `scope` but allows this scope to be interrupted.
    * Note that this may fail with `Interrupted` when interruption occurred
    */
  private[fs2] def interruptScope[F[_], O](
      s: Pull[F, O, Unit]
  )(implicit F: Concurrent[F]): Pull[F, O, Unit] =
    scope0(s, Some(F))

  private def scope0[F[_], O](
      s: Pull[F, O, Unit],
      interruptible: Option[Concurrent[F]]
  ): Pull[F, O, Unit] =
    OpenScope(interruptible).flatMap { scopeId =>
      s.transformWith {
        case Result.Succeeded(_) => CloseScope(scopeId, None, ExitCase.Completed)
        case interrupted @ Result.Interrupted(_, _) =>
          CloseScope(scopeId, Some(interrupted), ExitCase.Canceled)
        case Result.Fail(err) =>
          CloseScope(scopeId, None, ExitCase.Error(err)).transformWith {
            case Result.Succeeded(_)    => Result.Fail(err)
            case Result.Fail(err0) => Result.Fail(CompositeFailure(err, err0, Nil))
            case Result.Interrupted(interruptedScopeId, _) =>
              sys.error(
                s"Impossible, cannot interrupt when closing failed scope: $scopeId, $interruptedScopeId, $err"
              )
          }
      }
    }

  private[fs2] def uncons[F[_], X, O](s: Pull[F, O, Unit]): Pull[F, X, Option[(Chunk[O], Pull[F, O, Unit])]] =
    Step(s, None).map(_.map { case (h, _, t) => (h, t.asInstanceOf[Pull[F, O, Unit]]) })

  /* Left-folds the output of a stream.
   *
   * Interruption of the stream is tightly coupled between Pull, Algebra and CompileScope
   * Reason for this is unlike interruption of `F` type (i.e. IO) we need to find
   * recovery point where stream evaluation has to continue in Stream algebra
   *
   * As such the `Token` is passed to Pull.Result.Interrupted as glue between Pull/Algebra that allows pass-along
   * information for Algebra and scope to correctly compute recovery point after interruption was signalled via `CompilerScope`.
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
  )(g: (B, Chunk[O]) => B)(implicit F: MonadError[F, Throwable]): F[B] = {

    case class Done(scope: CompileScope[F]) extends R[INothing]
    case class Out[+X](head: Chunk[X], scope: CompileScope[F], tail: Pull[F, X, Unit]) extends R[X]
    case class Interrupted(scopeId: Token, err: Option[Throwable]) extends R[INothing]
    sealed trait R[+X]

    def go[X](
        scope: CompileScope[F],
        extendedTopLevelScope: Option[CompileScope[F]],
        stream: Pull[F, X, Unit]
    ): F[R[X]] =
      ViewL(stream) match {
        case _: Pull.Result.Succeeded[Unit] =>
          F.pure(Done(scope))

        case failed: Pull.Result.Fail =>
          F.raiseError(failed.error)

        case interrupted: Pull.Result.Interrupted =>
          F.pure(Interrupted(interrupted.context, interrupted.deferredError))

        case view: ViewL.View[F, X, y, Unit] =>
          def resume(res: Result[y]): F[R[X]] =
            go[X](scope, extendedTopLevelScope, view.next(res))

          def interruptGuard(scope: CompileScope[F])(next: => F[R[X]]): F[R[X]] =
            F.flatMap(scope.isInterrupted) {
              case None => next
              case Some(Left(err)) =>
                go(scope, extendedTopLevelScope, view.next(Result.Fail(err)))
              case Some(Right(scopeId)) =>
                go(scope, extendedTopLevelScope, view.next(Result.Interrupted(scopeId, None)))
            }
          view.step match {
            case output: Output[X] =>
              interruptGuard(scope)(
                F.pure(Out(output.values, scope, view.next(Pull.Result.unit)))
              )

            case u: Step[y] =>
              // if scope was specified in step, try to find it, otherwise use the current scope.
              F.flatMap(u.scope.fold[F[Option[CompileScope[F]]]](F.pure(Some(scope))) { scopeId =>
                scope.findStepScope(scopeId)
              }) {
                case Some(stepScope) =>
                  val stepStream = u.stream.asInstanceOf[Pull[F, y, Unit]]
                  F.flatMap(F.attempt(go[y](stepScope, extendedTopLevelScope, stepStream))) {
                    case Right(Done(scope)) =>
                      interruptGuard(scope)(
                        go(scope, extendedTopLevelScope, view.next(Result.Succeeded(None)))
                      )
                    case Right(Out(head, outScope, tail)) =>
                      // if we originally swapped scopes we want to return the original
                      // scope back to the go as that is the scope that is expected to be here.
                      val nextScope = if (u.scope.isEmpty) outScope else scope
                      val result = Result.Succeeded(Some((head, outScope.id, tail)))
                      interruptGuard(nextScope)(
                        go(nextScope, extendedTopLevelScope, view.next(result))
                      )

                    case Right(Interrupted(scopeId, err)) =>
                      go(scope, extendedTopLevelScope, view.next(Result.Interrupted(scopeId, err)))

                    case Left(err) =>
                      go(scope, extendedTopLevelScope, view.next(Result.Fail(err)))
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

            case eval: Eval[F, r] =>
              F.flatMap(scope.interruptibleEval(eval.value)) {
                case Right(r)           => resume(Result.Succeeded(r))
                case Left(Left(err))    => resume(Result.Fail(err))
                case Left(Right(token)) => resume(Result.Interrupted(token, None))
              }

            case acquire: Acquire[F, r] =>
              interruptGuard(scope) {
                F.flatMap(scope.acquireResource(acquire.resource, acquire.release)) { r =>
                  resume(Result.fromEither(r))
                }
              }

            case _: GetScope[_] =>
              resume(Result.Succeeded(scope.asInstanceOf[y]))

            case OpenScope(interruptibleX) =>
              val interruptible = interruptibleX.asInstanceOf[Option[Concurrent[F]]]
              interruptGuard(scope) {
                val maybeCloseExtendedScope: F[Boolean] =
                  // If we're opening a new top-level scope (aka, direct descendant of root),
                  // close the current extended top-level scope if it is defined.
                  if (scope.parent.isEmpty)
                    extendedTopLevelScope match {
                      case None    => false.pure[F]
                      case Some(s) => s.close(ExitCase.Completed).rethrow.as(true)
                    }
                  else F.pure(false)
                maybeCloseExtendedScope.flatMap { closedExtendedScope =>
                  val newExtendedScope = if (closedExtendedScope) None else extendedTopLevelScope
                  F.flatMap(scope.open(interruptible)) {
                    case Left(err) =>
                      go(scope, newExtendedScope, view.next(Result.Fail(err)))
                    case Right(childScope) =>
                      go(childScope, newExtendedScope, view.next(Result.Succeeded(childScope.id)))
                  }
                }
              }

            case close: CloseScope =>
              def closeAndGo(toClose: CompileScope[F], ec: ExitCase[Throwable]) =
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
                    go(ancestor, extendedTopLevelScope, view.next(res))
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
                    go(scope, extendedTopLevelScope, view.next(Result.unit))
                  else if (extendLastTopLevelScope && toClose.parent.flatMap(_.parent).isEmpty)
                    // Request to close the current top-level scope - if we're supposed to extend
                    // it instead, leave the scope open and pass it to the continuation
                    extendedTopLevelScope.traverse_(_.close(ExitCase.Completed).rethrow) *>
                      F.flatMap(toClose.openAncestor)(ancestor =>
                        go(ancestor, Some(toClose), view.next(Result.unit))
                      )
                  else closeAndGo(toClose, close.exitCase)
                case None =>
                  // scope already closed, continue with current scope
                  val result = close.interruption.getOrElse(Result.unit)
                  go(scope, extendedTopLevelScope, view.next(result))
              }
          }
      }

    def outerLoop(scope: CompileScope[F], accB: B, stream: Pull[F, O, Unit]): F[B] =
      F.flatMap(go(scope, None, stream)) {
        case Done(_) => F.pure(accB)
        case Out(head, scope, tail) =>
          try outerLoop(scope, g(accB, head), tail)
          catch {
            case NonFatal(err) => outerLoop(scope, accB, tail.asHandler(err))
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
              case Result.Succeeded(_)   => go(idx + 1)
              case Result.Fail(err) => Result.Fail(err)
              case interruption @ Result.Interrupted(_, _) =>
                flatMapOutput[F, F2, O, O2](interruptBoundary(tail, interruption), f)
            }

        go(0)
    }

  /**
    * Inject interruption to the tail used in flatMap.
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
            CloseScope(scopeId, Some(interruption), ExitCase.Canceled).transformWith(view.next)
          case _ =>
            // all other cases insert interruption cause
            view.next(interruption)
        }
    }

  private[fs2] def translate[F[_], G[_], O](
      stream: Pull[F, O, Unit],
      fK: F ~> G
  )(implicit G: TranslateInterrupt[G]): Pull[G, O, Unit] = {
    val concurrent: Option[Concurrent[G]] = G.concurrentInstance
    def translateAlgEffect[R](self: AlgEffect[F, R]): AlgEffect[G, R] =
      self match {
        // safe to cast, used in translate only
        // if interruption has to be supported concurrent for G has to be passed
        case a: Acquire[F, r] =>
          Acquire[G, r](fK(a.resource), (r, ec) => fK(a.release(r, ec)))
        case e: Eval[F, R]  => Eval[G, R](fK(e.value))
        case OpenScope(_)   => OpenScope[G](concurrent)
        case c: CloseScope  => c
        case g: GetScope[_] => g
      }

    def translateStep[X](next: Pull[F, X, Unit], isMainLevel: Boolean): Pull[G, X, Unit] =
      ViewL(next) match {
        case result: Result[Unit] => result

        case view: ViewL.View[F, X, y, Unit] =>
          view.step match {
            case output: Output[X] =>
              output.transformWith {
                case r @ Result.Succeeded(_) if isMainLevel =>
                  translateStep(view.next(r), isMainLevel)

                case r @ Result.Succeeded(_) if !isMainLevel =>
                  // Cast is safe here, as at this point the evaluation of this Step will end
                  // and the remainder of the free will be passed as a result in Bind. As such
                  // next Step will have this to evaluate, and will try to translate again.
                  view.next(r).asInstanceOf[Pull[G, X, Unit]]

                case r @ Result.Fail(_) => translateStep(view.next(r), isMainLevel)

                case r @ Result.Interrupted(_, _) => translateStep(view.next(r), isMainLevel)
              }

            case step: Step[x] =>
              // NOTE: The use of the `asInstanceOf` is to by-pass a compiler-crash in Scala 2.12,
              // involving GADTs with a covariant Higher-Kinded parameter.
              Step[x](
                stream = translateStep[x](step.stream.asInstanceOf[Pull[F, x, Unit]], false),
                scope = step.scope
              ).transformWith { r =>
                translateStep[X](view.next(r.asInstanceOf[Result[y]]), isMainLevel)
              }

            case alg: AlgEffect[F, r] =>
              translateAlgEffect(alg)
                .transformWith(r => translateStep(view.next(r), isMainLevel))
          }
      }

    translateStep[O](stream, true)
  }
}

private[fs2] trait PullLowPriority {
  implicit def monadInstance[F[_], O]: Monad[Pull[F, O, *]] =
    new PullSyncInstance[F, O]
}

private[fs2] class PullSyncInstance[F[_], O] extends Sync[Pull[F, O, *]] {
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
  def suspend[R](p: => Pull[F, O, R]) = Pull.suspend(p)
  def bracketCase[A, B](acquire: Pull[F, O, A])(
      use: A => Pull[F, O, B]
  )(release: (A, ExitCase[Throwable]) => Pull[F, O, Unit]): Pull[F, O, B] =
      Pull.bracketCase(acquire, use, release)
}
