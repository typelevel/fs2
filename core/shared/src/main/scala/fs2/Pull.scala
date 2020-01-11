package fs2

import cats.arrow.FunctionK
import cats.effect._
import cats.implicits._
import cats.{ApplicativeError, Monad, MonadError, Eval => _, ~>}
import fs2.internal.{CompileScope, Token, TranslateInterrupt}
import scala.annotation.tailrec
import scala.util.control.NonFatal
import PullImpl._

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
sealed abstract class Pull[+F[_], +O, +R] {

  /** Alias for `_.map(_ => o2)`. */
  def as[R2](r2: R2): Pull[F, O, R2] = map(_ => r2)

  /** Returns a pull with the result wrapped in `Right`, or an error wrapped in `Left` if the pull has failed. */
  def attempt: Pull[F, O, Either[Throwable, R]] =
    map(r => Right(r)).handleErrorWith(t => Value(Left(t)))

  /**
    * Interpret this `Pull` to produce a `Stream`, introducing a scope.
    *
    * May only be called on pulls which return a `Unit` result type. Use `p.void.stream` to explicitly
    * ignore the result type of the pull.
    */
  def stream(implicit ev: R <:< Unit): Stream[F, O] = {
    val _ = ev
    new Stream(this.asInstanceOf[Pull[F, O, Unit]])
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
    new Stream(PullImpl.scoped(this.asInstanceOf[Pull[F, O, Unit]], None))
  }

  /** Applies the resource of this pull to `f` and returns the result. */
  def flatMap[F2[x] >: F[x], O2 >: O, R2](f: R => Pull[F2, O2, R2]): Pull[F2, O2, R2]

  /** Alias for `flatMap(_ => p2)`. */
  def >>[F2[x] >: F[x], O2 >: O, R2](p2: => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    flatMap(_ => p2)

  /** Lifts this pull to the specified effect type. */
  def covary[F2[x] >: F[x]]: Pull[F2, O, R] = this

  /** Lifts this pull to the specified effect type, output type, and resource type. */
  def covaryAll[F2[x] >: F[x], O2 >: O, R2 >: R]: Pull[F2, O2, R2] = this

  /** Lifts this pull to the specified output type. */
  def covaryOutput[O2 >: O]: Pull[F, O2, R] = this

  /** Lifts this pull to the specified resource type. */
  def covaryResource[R2 >: R]: Pull[F, O, R2] = this

  /** Applies the resource of this pull to `f` and returns the result in a new `Pull`. */
  def map[R2](f: R => R2): Pull[F, O, R2]

  /** Applies the outputs of this pull to `f` and returns the result in a new `Pull`. */
  def mapOutput[O2](f: O => O2): Pull[F, O2, R]

  /** Run `p2` after `this`, regardless of errors during `this`, then reraise any errors encountered during `this`. */
  def onComplete[F2[x] >: F[x], O2 >: O, R2 >: R](p2: => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    handleErrorWith(e => p2 >> Fail(e)) >> p2

  /** If `this` terminates with `Pull.raiseError(e)`, invoke `h(e)`. */
  def handleErrorWith[F2[x] >: F[x], O2 >: O, R2 >: R](
      h: Throwable => Pull[F2, O2, R2]
  ): Pull[F2, O2, R2]

  /** Discards the result type of this pull. */
  def void: Pull[F, O, Unit] = as(())

  def append[F2[x] >: F[x], O2 >: O, R2](post: => Pull[F2, O2, R2]): Pull[F2, O2, R2]

  private[fs2] def transformWith[F2[x] >: F[x], O2 >: O, R2](
      f: PullImpl.Result[R] => Pull[F2, O2, R2]
  ): Pull[F2, O2, R2]

  private[fs2] def asHandler(e: Throwable): Pull[F, O, R]

  private[fs2] def viewL[F2[x] >: F[x], O2 >: O, R2 >: R]: ViewL[F2, O2, R2] = PullImpl.ViewL(this)
}

object Pull extends PullLowPriority {

  /** The completed `Pull`. Reads and outputs nothing. */
  val unit: Pull[Pure, INothing, Unit] = Value(())

  val done = unit

  /**
    * Like [[eval]] but if the effectful value fails, the exception is returned in a `Left`
    * instead of failing the pull.
    */
  def attemptEval[F[_], R](fr: F[R]): Pull[F, INothing, Either[Throwable, R]] =
    Eval[F, R](fr).attempt

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
  def output1[F[x] >: Pure[x], O](o: O): Pull[F, O, Unit] =
    Output[O](Chunk.singleton(o))

  /** Outputs a chunk of values. */
  def output[F[x] >: Pure[x], O](os: Chunk[O]): Pull[F, O, Unit] =
    if (os.isEmpty) unit else new Output[O](os)

  /** Pull that outputs nothing and has result of `r`. */
  def pure[F[x] >: Pure[x], R](r: R): Pull[F, INothing, R] =
    Value(r)

  /**
    * Reads and outputs nothing, and fails with the given error.
    *
    * The `F` type must be explicitly provided (e.g., via `raiseError[IO]` or `raiseError[Fallible]`).
    */
  def raiseError[F[_]: RaiseThrowable](err: Throwable): Pull[F, INothing, INothing] =
    Fail(err)

  final class PartiallyAppliedFromEither[F[_]] {
    def apply[A](either: Either[Throwable, A])(implicit ev: RaiseThrowable[F]): Pull[F, A, Unit] =
      either.fold(Pull.raiseError[F], Pull.output1)
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
    new Bind[F, O, Unit, R](unit) {
      def cont(r: Result[Unit]): Pull[F, O, R] = p
    }

  /** `Sync` instance for `Pull`. */
  implicit def syncInstance[F[_], O](implicit
      ev: ApplicativeError[F, Throwable]
  ): Sync[Pull[F, O, ?]] = {
    val _ = ev
    new PullSyncInstance[F, O]
  }

  /**
    * `FunctionK` instance for `F ~> Pull[F, INothing, ?]`
    *
    * @example {{{
    * scala> import cats.Id
    * scala> Pull.functionKInstance[Id](42).flatMap(Pull.output1).stream.compile.toList
    * res0: cats.Id[List[Int]] = List(42)
    * }}}
    */
  implicit def functionKInstance[F[_]]: F ~> Pull[F, INothing, ?] =
    FunctionK.lift[F, Pull[F, INothing, ?]](Pull.eval)
}

private[fs2] trait PullLowPriority {
  implicit def monadInstance[F[_], O]: Monad[Pull[F, O, ?]] =
    new PullSyncInstance[F, O]
}

private[fs2] class PullSyncInstance[F[_], O] extends Sync[Pull[F, O, ?]] {
  def pure[A](a: A): Pull[F, O, A] = Pull.pure(a)
  def handleErrorWith[A](p: Pull[F, O, A])(h: Throwable => Pull[F, O, A]) =
    p.handleErrorWith(h)
  def raiseError[A](t: Throwable) = Pull.raiseError[F](t)
  def flatMap[A, B](p: Pull[F, O, A])(f: A => Pull[F, O, B]) = p.flatMap(f)
  def tailRecM[A, B](a: A)(f: A => Pull[F, O, Either[A, B]]) =
    f(a).flatMap {
      case Left(a)  => tailRecM(a)(f)
      case Right(b) => Pull.pure(b)
    }
  def suspend[R](p: => Pull[F, O, R]) = Pull.suspend(p)
  def bracketCase[A, B](acquire: Pull[F, O, A])(
    use: A => Pull[F, O, B]
  )(release: (A, ExitCase[Throwable]) => Pull[F, O, Unit]): Pull[F, O, B] =
    PullImpl.bracketCase(acquire, (a: A) => use(a), (a: A, c) => release(a, c))
}


/**
  * Contains the implementation of the Pull interface, as a GADT that models a Free-Monad.
  *
  * The essence of as a free Monad with Catch (and Interruption).
  * [[Pull]] provides mechanism for ensuring stack safety and capturing any exceptions that may arise during computation.
  *
  * Furthermore, it may capture Interruption of the evaluation, although [[Pull]] itself does not have any
  * interruptible behaviour per se.
  *
  * Interruption cause may be captured in [[Interrupted]] and allows user to pass along any information relevant
  * to interpreter.
  *
  * Typically the [[Pull]] user provides interpretation of Pull in form of [[ViewL]] structure, that allows to step
  * Pull via series of Results ([[Pure]], [[Fail]] and [[Interrupted]]) and Pull step ([[ViewL.View]])
  */
private[fs2] object PullImpl {

  sealed abstract class PullC[+F[_], +O, +R] extends Pull[F, O, R] {

    override def flatMap[F2[x] >: F[x], O2 >: O, R2](f: R => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
      new Bind[F2, O2, R, R2](this) {
        def cont(e: Result[R]): Pull[F2, O2, R2] = e match {
          case Value(r) =>
            try f(r)
            catch { case NonFatal(e) => Fail(e) }
          case res @ Interrupted(_, _) => res
          case res @ Fail(_)           => res
        }
      }

    override def append[F2[x] >: F[x], O2 >: O, R2](post: => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
      new Bind[F2, O2, R, R2](this) {
        def cont(r: Result[R]): Pull[F2, O2, R2] = r match {
          case _: Value[_]       => post
          case r: Interrupted[_] => r
          case r: Fail           => r
        }
      }

    override def transformWith[F2[x] >: F[x], O2 >: O, R2](
        f: Result[R] => Pull[F2, O2, R2]
    ): Pull[F2, O2, R2] =
      new Bind[F2, O2, R, R2](this) {
        def cont(r: Result[R]): Pull[F2, O2, R2] =
          try f(r)
          catch { case NonFatal(e) => Fail(e) }
      }

    override def map[R2](f: R => R2): Pull[F, O, R2] =
      new Bind[F, O, R, R2](this) {
        def cont(e: Result[R]): Pull[F, O, R2] = Result.map(e)(f)
      }

    def handleErrorWith[F2[x] >: F[x], O2 >: O, R2 >: R](
        h: Throwable => Pull[F2, O2, R2]
    ): Pull[F2, O2, R2] =
      new Bind[F2, O2, R2, R2](this) {
        def cont(e: Result[R2]): Pull[F2, O2, R2] = e match {
          case Fail(e) =>
            try h(e)
            catch { case NonFatal(e) => Fail(e) }
          case other => other
        }
      }

    override def asHandler(e: Throwable): Pull[F, O, R] = ViewL(this) match {
      case Value(_) => Fail(e)
      case Fail(e2) => Fail(CompositeFailure(e2, e))
      case Interrupted(ctx, err) =>
        Interrupted(ctx, err.map(t => CompositeFailure(e, t)).orElse(Some(e)))
      case v @ ViewL.View(_) => v.next(Fail(e))
    }

  }

  sealed abstract class Result[+R] extends PullC[Pure, INothing, R] with ViewL[Pure, INothing, R] {
    self =>
    override def mapOutput[P](f: INothing => P): Pull[Pure, INothing, R] = this

    def asExitCase: ExitCase[Throwable] = self match {
      case Value(_)          => ExitCase.Completed
      case Fail(err)         => ExitCase.Error(err)
      case Interrupted(_, _) => ExitCase.Canceled
    }
  }

  final case class Value[+R](r: R) extends Result[R] {
    override def toString: String = s"Pull.Value($r)"
  }

  final case class Fail(error: Throwable) extends Result[INothing] {
    override def toString: String = s"Pull.Fail($error)"
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
  final case class Interrupted[X](context: X, deferredError: Option[Throwable])
      extends Result[INothing] {
    override def toString: String =
      s"Pull.Interrupted($context, ${deferredError.map(_.getMessage)})"
  }

  val unit: Value[Unit] = Value(())

  object Result {

    def fromEither[R](either: Either[Throwable, R]): Result[R] =
      either.fold(Fail(_), Value(_))

    private[PullImpl] def map[A, B](fa: Result[A])(f: A => B): Result[B] = fa match {
      case Value(r) =>
        try Value(f(r))
        catch { case NonFatal(err) => Fail(err) }
      case failure @ Fail(_)               => failure
      case interrupted @ Interrupted(_, _) => interrupted
    }
  }

  /** Represents the Bind (or flatMap) operation of the Pull implementaiton Free Monad.
    */
  abstract class Bind[F[_], O, X, R](val step: Pull[F, O, X]) extends PullC[F, O, R] {
    def cont(r: Result[X]): Pull[F, O, R]
    def delegate: Bind[F, O, X, R] = this

    override def mapOutput[P](f: O => P): Pull[F, P, R] = Pull.suspend {
      viewL match {
        case v: ViewL.View[F, O, x, R] =>
          new Bind[F, P, x, R](v.step.mapOutput(f)) {
            def cont(e: Result[x]) = v.next(e).mapOutput(f)
          }
        case r: Result[_] => r
      }
    }

    override def toString: String = s"Pull.Bind($step)"
  }

  /* `Eval[F[_], O, R]` is a Generalised Algebraic Data Type (GADT)
   * of atomic instructions that can be evaluated in the effect `F`
   * to generate by-product outputs of type `O`.
   *
   * Each operation also generates an output of type `R` that is used
   * as control information for the rest of the interpretation or compilation.
   */
  sealed abstract class Action[+F[_], +O, +R] extends PullC[F, O, R]

  final case class Output[O](values: Chunk[O]) extends Action[Pure, O, Unit] {
    override def mapOutput[P](f: O => P): Pull[Pure, P, Unit] =
      Pull.suspend {
        try Output(values.map(f))
        catch { case NonFatal(t) => Fail(t) }
      }
  }

  /**
    * Steps through the stream, providing either `uncons` or `stepLeg`.
    * Yields to head in form of chunk, then id of the scope that was active after step evaluated and tail of the `stream`.
    *
    * @param stream             Stream to step
    * @param scopeId            If scope has to be changed before this step is evaluated, id of the scope must be supplied
    */
  final case class Step[X](stream: Pull[Any, X, Unit], scope: Option[Token])
      extends Action[Pure, INothing, Option[(Chunk[X], Token, Pull[Any, X, Unit])]] {
    /* NOTE: The use of `Any` and `Pure` done to by-pass an error in Scala 2.12 type-checker,
     * that produces a crash when dealing with Higher-Kinded GADTs in which the F parameter appears
     * Inside one of the values of the case class.      */
    override def mapOutput[P](f: INothing => P): Step[X] = this
  }

  /* The `AlgEffect` trait is for operations on the `F` effect that create no `O` output.
   * They are related to resources and scopes. */
  sealed abstract class AlgEffect[+F[_], R] extends Action[F, INothing, R] {
    final def mapOutput[P](f: INothing => P): Pull[F, P, R] = this
  }

  final case class Eval[+F[_], R](value: F[R]) extends AlgEffect[F, R]

  final case class Acquire[+F[_], R](
      resource: F[R],
      release: (R, ExitCase[Throwable]) => F[Unit]
  ) extends AlgEffect[F, R]
  // NOTE: The use of a separate `G` and `Pure` is done o by-pass a compiler-crash in Scala 2.12,
  // involving GADTs with a covariant Higher-Kinded parameter. */
  final case class OpenScope[G[_]](interruptible: Option[Concurrent[G]])
      extends AlgEffect[Pure, Token]

  // `InterruptedScope` contains id of the scope currently being interrupted
  // together with any errors accumulated during interruption process
  final case class CloseScope(
      scopeId: Token,
      interruptedScope: Option[(Token, Option[Throwable])],
      exitCase: ExitCase[Throwable]
  ) extends AlgEffect[Pure, Unit]

  final case class GetScope[F[_]]() extends AlgEffect[Pure, CompileScope[F]]

  /**
    * Unrolled view of a `Pull` structure. may be `Result` or `EvalBind`
    */
  sealed trait ViewL[+F[_], +O, +R]

  object ViewL {

    /** unrolled view of Pull `bind` structure **/
    sealed abstract case class View[+F[_], O, X, R](step: Action[F, O, X]) extends ViewL[F, O, R] {
      def next(r: Result[X]): Pull[F, O, R]
    }

    private[ViewL] final class EvalView[+F[_], O, R](step: Action[F, O, R])
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

  private[this] def asHandler[F[_], O, R](p: Pull[F, O, R], e: Throwable): Pull[F, O, R] =
    p.viewL match {
      case Value(_) => Fail(e)
      case Fail(e2) => Fail(CompositeFailure(e2, e))
      case Interrupted(ctx, err) =>
        Interrupted(ctx, err.map(t => CompositeFailure(e, t)).orElse(Some(e)))
      case v @ ViewL.View(_) => v.next(Fail(e))
    }

  def bracketCase[F[_], O, A, B](
      acquire: Pull[F, O, A],
      use: A => Pull[F, O, B],
      release: (A, ExitCase[Throwable]) => Pull[F, O, Unit]
  ): Pull[F, O, B] =
    acquire.flatMap { a =>
      val used =
        try use(a)
        catch { case NonFatal(t) => Fail(t) }
      used.transformWith { result =>
        release(a, result.asExitCase).transformWith {
          case Fail(t2) =>
            result match {
              case Fail(tres) => Fail(CompositeFailure(tres, t2))
              case result     => result
            }
          case _ => result
        }
      }
    }

  def stepLeg[F[_], O](leg: Stream.StepLeg[F, O]): Pull[F, Nothing, Option[Stream.StepLeg[F, O]]] =
    Step[O](leg.next, Some(leg.scopeId)).map {
      _.map {
        case (h, id, t) => new Stream.StepLeg[F, O](h, id, t.asInstanceOf[Pull[F, O, Unit]])
      }
    }

  def scoped[F[_], O](s: Pull[F, O, Unit], interruptible: Option[Concurrent[F]]): Pull[F, O, Unit] =
    OpenScope(interruptible).flatMap { scopeId =>
      s.transformWith {
        case Value(_) =>
          CloseScope(scopeId, interruptedScope = None, ExitCase.Completed)
        case Interrupted(interruptedScopeId: Token, err) =>
          CloseScope(scopeId, interruptedScope = Some((interruptedScopeId, err)), ExitCase.Canceled)
        case Fail(err) =>
          CloseScope(scopeId, interruptedScope = None, ExitCase.Error(err)).transformWith {
            case Value(_)   => Fail(err)
            case Fail(err0) => Fail(CompositeFailure(err, err0, Nil))
            case Interrupted(interruptedScopeId, _) =>
              sys.error(
                s"Impossible, cannot interrupt when closing failed scope: $scopeId, $interruptedScopeId, $err"
              )
          }

        case Interrupted(ctx, _) => sys.error(s"Impossible context: $ctx")
      }
    }

  def translate[F[_], G[_], O](
      s: Pull[F, O, Unit],
      u: F ~> G
  )(implicit G: TranslateInterrupt[G]): Pull[G, O, Unit] =
    translate0[F, G, O](u, s, G.concurrentInstance)

  def uncons[F[_], X, O](s: Pull[F, O, Unit]): Pull[F, X, Option[(Chunk[O], Pull[F, O, Unit])]] =
    Step(s, None).map { _.map { case (h, _, t) => (h, t.asInstanceOf[Pull[F, O, Unit]]) } }

  /** Left-folds the output of a stream. */
  def compile[F[_], O, B](
      stream: Pull[F, O, Unit],
      scope: CompileScope[F],
      extendLastTopLevelScope: Boolean,
      init: B
  )(g: (B, Chunk[O]) => B)(implicit F: MonadError[F, Throwable]): F[B] =
    compileLoop[F, O](scope, extendLastTopLevelScope, stream).flatMap {
      case Some((output, scope, tail)) =>
        try {
          val b = g(init, output)
          compile(tail, scope, extendLastTopLevelScope, b)(g)
        } catch {
          case NonFatal(err) =>
            compile(asHandler(tail, err), scope, extendLastTopLevelScope, init)(g)
        }
      case None =>
        F.pure(init)
    }

  /*
   * Interruption of the stream is tightly coupled between Pull, Algebra and CompileScope
   * Reason for this is unlike interruption of `F` type (i.e. IO) we need to find
   * recovery point where stream evaluation has to continue in Stream algebra
   *
   * As such the `Token` is passed to Pull.Interrupted as glue between Pull/Algebra that allows pass-along
   * information for Algebra and scope to correctly compute recovery point after interruption was signalled via `CompilerScope`.
   *
   * This token indicates scope of the computation where interruption actually happened.
   * This is used to precisely find most relevant interruption scope where interruption shall be resumed
   * for normal continuation of the stream evaluation.
   *
   * Interpreter uses this to find any parents of this scope that has to be interrupted, and guards the
   * interruption so it won't propagate to scope that shall not be anymore interrupted.
   */
  private[this] def compileLoop[F[_], O](
      scope: CompileScope[F],
      extendLastTopLevelScope: Boolean,
      stream: Pull[F, O, Unit]
  )(
      implicit F: MonadError[F, Throwable]
  ): F[Option[(Chunk[O], CompileScope[F], Pull[F, O, Unit])]] = {
    case class Done[X](scope: CompileScope[F]) extends R[X]
    case class Out[X](head: Chunk[X], scope: CompileScope[F], tail: Pull[F, X, Unit]) extends R[X]
    case class Interru[X](scopeId: Token, err: Option[Throwable]) extends R[X]
    sealed trait R[X]

    def go[X](
        scope: CompileScope[F],
        extendedTopLevelScope: Option[CompileScope[F]],
        stream: Pull[F, X, Unit]
    ): F[R[X]] =
      ViewL(stream) match {
        case _: Value[Unit] =>
          F.pure(Done(scope))

        case failed: Fail =>
          F.raiseError(failed.error)

        case interrupted: Interrupted[_] =>
          interrupted.context match {
            case scopeId: Token => F.pure(Interru(scopeId, interrupted.deferredError))
            case other          => sys.error(s"Unexpected interruption context: $other (compileLoop)")
          }

        case view: ViewL.View[F, X, y, Unit] =>
          def resume(res: Result[y]): F[R[X]] =
            go[X](scope, extendedTopLevelScope, view.next(res))

          def interruptGuard(scope: CompileScope[F])(next: => F[R[X]]): F[R[X]] =
            F.flatMap(scope.isInterrupted) {
              case None => next
              case Some(Left(err)) =>
                go(scope, extendedTopLevelScope, view.next(Fail(err)))
              case Some(Right(scopeId)) =>
                go(scope, extendedTopLevelScope, view.next(Interrupted(scopeId, None)))
            }
          view.step match {
            case output: Output[X] =>
              interruptGuard(scope)(
                F.pure(Out(output.values, scope, view.next(Value(()))))
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
                        go(scope, extendedTopLevelScope, view.next(Value(None)))
                      )
                    case Right(Out(head, outScope, tail)) =>
                      // if we originally swapped scopes we want to return the original
                      // scope back to the go as that is the scope that is expected to be here.
                      val nextScope = if (u.scope.isEmpty) outScope else scope
                      val result = Value(Some((head, outScope.id, tail)))
                      interruptGuard(nextScope)(
                        go(nextScope, extendedTopLevelScope, view.next(result))
                      )

                    case Right(Interru(scopeId, err)) =>
                      go(scope, extendedTopLevelScope, view.next(Interrupted(scopeId, err)))

                    case Left(err) =>
                      go(scope, extendedTopLevelScope, view.next(Fail(err)))
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
                case Right(r)           => resume(Value(r))
                case Left(Left(err))    => resume(Fail(err))
                case Left(Right(token)) => resume(Interrupted(token, None))
              }

            case acquire: Acquire[F, r] =>
              interruptGuard(scope) {
                F.flatMap(scope.acquireResource(acquire.resource, acquire.release)) { r =>
                  resume(Result.fromEither(r))
                }
              }

            case _: GetScope[_] =>
              resume(Value(scope.asInstanceOf[y]))

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
                      go(scope, newExtendedScope, view.next(Fail(err)))
                    case Right(childScope) =>
                      go(childScope, newExtendedScope, view.next(Value(childScope.id)))
                  }
                }
              }

            case close: CloseScope =>
              def closeAndGo(toClose: CompileScope[F], ec: ExitCase[Throwable]) =
                F.flatMap(toClose.close(ec)) { r =>
                  F.flatMap(toClose.openAncestor) { ancestor =>
                    val res = close.interruptedScope match {
                      case None => Result.fromEither(r)
                      case Some((interruptedScopeId, err)) =>
                        def err1 = CompositeFailure.fromList(r.swap.toOption.toList ++ err.toList)
                        if (ancestor.findSelfOrAncestor(interruptedScopeId).isDefined) {
                          // we still have scopes to interrupt, lets build interrupted tail
                          Interrupted(interruptedScopeId, err1)
                        } else {
                          // interrupts scope was already interrupted, resume operation
                          err1 match {
                            case None      => unit
                            case Some(err) => Fail(err)
                          }
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
                  if (toClose.parent.isEmpty) {
                    // Impossible - don't close root scope as a result of a `CloseScope` call
                    go(scope, extendedTopLevelScope, view.next(unit))
                  } else if (extendLastTopLevelScope && toClose.parent.flatMap(_.parent).isEmpty) {
                    // Request to close the current top-level scope - if we're supposed to extend
                    // it instead, leave the scope open and pass it to the continuation
                    extendedTopLevelScope.traverse_(_.close(ExitCase.Completed).rethrow) *>
                      F.flatMap(toClose.openAncestor)(ancestor =>
                        go(ancestor, Some(toClose), view.next(unit))
                      )
                  } else closeAndGo(toClose, close.exitCase)
                case None =>
                  // scope already closed, continue with current scope
                  val result = close.interruptedScope match {
                    case Some((x, y)) => Interrupted(x, y)
                    case None         => unit
                  }
                  go(scope, extendedTopLevelScope, view.next(result))
              }
          }
      }

    F.flatMap(go(scope, None, stream)) {
      case Done(_)                => F.pure(None)
      case Out(head, scope, tail) => F.pure(Some((head, scope, tail)))
      case Interru(_, err) =>
        err match {
          case None      => F.pure(None)
          case Some(err) => F.raiseError(err)
        }
    }
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
  def interruptBoundary[F[_], O](
      stream: Pull[F, O, Unit],
      interruptedScope: Token,
      interruptedError: Option[Throwable]
  ): Pull[F, O, Unit] =
    ViewL(stream) match {
      case _: Value[Unit] =>
        Interrupted(interruptedScope, interruptedError)
      case failed: Fail =>
        Fail(
          CompositeFailure
            .fromList(interruptedError.toList :+ failed.error)
            .getOrElse(failed.error)
        )
      case interrupted: Interrupted[_] =>
        // impossible
        Interrupted(interrupted.context, interrupted.deferredError)

      case view: ViewL.View[F, O, _, Unit] =>
        view.step match {
          case close: CloseScope =>
            CloseScope(
              close.scopeId,
              Some((interruptedScope, interruptedError)),
              ExitCase.Canceled
            ) // Inner scope is getting closed b/c a parent was interrupted
              .transformWith(view.next)
          case _ =>
            // all other cases insert interruption cause
            view.next(Interrupted(interruptedScope, interruptedError))
        }
    }

  private def translate0[F[_], G[_], O](
      fK: F ~> G,
      stream: Pull[F, O, Unit],
      concurrent: Option[Concurrent[G]]
  ): Pull[G, O, Unit] = {
    def translateAlgEffect[R](self: AlgEffect[F, R]): AlgEffect[G, R] = self match {
      // safe to cast, used in translate only
      // if interruption has to be supported concurrent for G has to be passed
      case a: Acquire[F, r] => Acquire(fK(a.resource), (r, ec) => fK(a.release(r, ec)))
      case e: Eval[F, R]    => Eval[G, R](fK(e.value))
      case OpenScope(_)     => OpenScope[G](concurrent)
      case c: CloseScope    => c
      case g: GetScope[_]   => g
    }

    def translateStep[X](next: Pull[F, X, Unit], isMainLevel: Boolean): Pull[G, X, Unit] =
      ViewL(next) match {
        case result: Result[Unit] => result

        case view: ViewL.View[F, X, y, Unit] =>
          view.step match {
            case output: Output[X] =>
              output.transformWith {
                case r @ Value(_) if isMainLevel =>
                  translateStep(view.next(r), isMainLevel)

                case r @ Value(_) if !isMainLevel =>
                  // Cast is safe here, as at this point the evaluation of this Step will end
                  // and the remainder of the free will be passed as a result in Bind. As such
                  // next Step will have this to evaluate, and will try to translate again.
                  view.next(r).asInstanceOf[Pull[G, X, Unit]]

                case r @ Fail(_) => translateStep(view.next(r), isMainLevel)

                case r @ Interrupted(_, _) => translateStep(view.next(r), isMainLevel)
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
