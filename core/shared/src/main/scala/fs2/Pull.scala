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
import cats.effect.kernel._
import cats.syntax.all._

import fs2.internal._
import Resource.ExitCase
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

  /** Returns a pull with the result wrapped in `Right`, or an error wrapped in `Left` if the pull has failed. */
  def attempt: Pull[F, O, Either[Throwable, R]] =
    map(r => Right(r)).handleErrorWith(t => Succeeded(Left(t)))

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
      def cont(e: Terminal[R]): Pull[F2, O2, R2] =
        e match {
          case Succeeded(r) =>
            try f(r)
            catch { case NonFatal(e) => Fail(e) }
          case res @ Interrupted(_, _) => res
          case res @ Fail(_)           => res
        }
    }

  /** Alias for `flatMap(_ => p2)`. */
  def >>[F2[x] >: F[x], O2 >: O, R2](p2: => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    new Bind[F2, O2, R, R2](this) {
      def cont(r: Terminal[R]): Pull[F2, O2, R2] =
        r match {
          case _: Succeeded[_] => p2
          case r: Interrupted  => r
          case r: Fail         => r
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
      def cont(r: Terminal[R]) = r.map(f)
    }

  /** Run `p2` after `this`, regardless of errors during `this`, then reraise any errors encountered during `this`. */
  def onComplete[F2[x] >: F[x], O2 >: O, R2 >: R](p2: => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    handleErrorWith(e => p2 >> Fail(e)) >> p2

  /** If `this` terminates with `Pull.raiseError(e)`, invoke `h(e)`. */
  def handleErrorWith[F2[x] >: F[x], O2 >: O, R2 >: R](
      h: Throwable => Pull[F2, O2, R2]
  ): Pull[F2, O2, R2] =
    new Bind[F2, O2, R2, R2](this) {
      def cont(e: Terminal[R2]): Pull[F2, O2, R2] =
        e match {
          case Fail(e) =>
            try h(e)
            catch { case NonFatal(e) => Fail(e) }
          case other => other
        }
    }

  /** Discards the result type of this pull. */
  def void: Pull[F, O, Unit] = as(())
}

object Pull extends PullLowPriority {

  private[fs2] def acquire[F[_], R](
      resource: F[R],
      release: (R, ExitCase) => F[Unit]
  ): Pull[F, INothing, R] =
    Acquire(resource, release, cancelable = false)

  private[fs2] def acquireCancelable[F[_], R](
      resource: Poll[F] => F[R],
      release: (R, ExitCase) => F[Unit]
  )(implicit F: MonadCancel[F, _]): Pull[F, INothing, R] =
    Acquire(F.uncancelable(resource), release, cancelable = true)

  /** Like [[eval]] but if the effectful value fails, the exception is returned in a `Left`
    * instead of failing the pull.
    */
  def attemptEval[F[_], R](fr: F[R]): Pull[F, INothing, Either[Throwable, R]] =
    Eval[F, R](fr)
      .map(r => Right(r): Either[Throwable, R])
      .handleErrorWith(t => Succeeded[Either[Throwable, R]](Left(t)))

  def bracketCase[F[_], O, A, B](
      acquire: Pull[F, O, A],
      use: A => Pull[F, O, B],
      release: (A, ExitCase) => Pull[F, O, Unit]
  ): Pull[F, O, B] =
    acquire.flatMap { a =>
      val used =
        try use(a)
        catch { case NonFatal(t) => Fail(t) }
      transformWith(used) { result =>
        val exitCase: ExitCase = result match {
          case Succeeded(_)      => ExitCase.Succeeded
          case Fail(err)         => ExitCase.Errored(err)
          case Interrupted(_, _) => ExitCase.Canceled
        }

        transformWith(release(a, exitCase)) {
          case Fail(t2) =>
            result match {
              case Fail(tres) => Fail(CompositeFailure(tres, t2))
              case result     => result
            }
          case _ => result
        }
      }
    }

  /** The completed `Pull`. Reads and outputs nothing. */
  private val unit: Terminal[Unit] = Succeeded(())

  /** The completed `Pull`. Reads and outputs nothing. */
  val done: Pull[Pure, INothing, Unit] = unit

  /** Evaluates the supplied effectful value and returns the result as the resource of the returned pull. */
  def eval[F[_], R](fr: F[R]): Pull[F, INothing, R] = Eval[F, R](fr)

  /** Extends the scope of the currently open resources to the specified stream, preventing them
    * from being finalized until after `s` completes execution, even if the returned pull is converted
    * to a stream, compiled, and evaluated before `s` is compiled and evaluated.
    */
  def extendScopeTo[F[_], O](
      s: Stream[F, O]
  )(implicit F: MonadError[F, Throwable]): Pull[F, INothing, Stream[F, O]] =
    for {
      scope <- Pull.getScope[F]
      lease <- Pull.eval(scope.lease)
    } yield s.onFinalize(lease.cancel.redeemWith(F.raiseError(_), _ => F.unit))

  /** Repeatedly uses the output of the pull as input for the next step of the pull.
    * Halts when a step terminates with `None` or `Pull.raiseError`.
    */
  def loop[F[_], O, R](f: R => Pull[F, O, Option[R]]): R => Pull[F, O, Unit] =
    (r: R) =>
      f(r).flatMap {
        case None    => Pull.done
        case Some(s) => loop(f)(s)
      }

  /** Outputs a single value. */
  def output1[F[x] >: Pure[x], O](o: O): Pull[F, O, Unit] = Output(Chunk.singleton(o))

  /** Outputs a chunk of values. */
  def output[F[x] >: Pure[x], O](os: Chunk[O]): Pull[F, O, Unit] =
    if (os.isEmpty) Pull.done else Output[O](os)

  /** Pull that outputs nothing and has result of `r`. */
  def pure[F[x] >: Pure[x], R](r: R): Pull[F, INothing, R] = Succeeded(r)

  /** Reads and outputs nothing, and fails with the given error.
    *
    * The `F` type must be explicitly provided (e.g., via `raiseError[IO]` or `raiseError[Fallible]`).
    */
  def raiseError[F[_]: RaiseThrowable](err: Throwable): Pull[F, INothing, INothing] = Fail(err)

  private[fs2] def fail[F[_]](err: Throwable): Pull[F, INothing, INothing] = Fail(err)

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
  private[fs2] def getScope[F[_]]: Pull[F, INothing, Scope[F]] = GetScope[F]()

  /** Returns a pull that evaluates the supplied by-name each time the pull is used,
    * allowing use of a mutable value in pull computations.
    */
  def suspend[F[x] >: Pure[x], O, R](p: => Pull[F, O, R]): Pull[F, O, R] =
    new Bind[F, O, Unit, R](unit) {
      def cont(r: Terminal[Unit]): Pull[F, O, R] = p
    }

  /** An abstraction for writing `Pull` computations that can timeout
    * while reading from a `Stream`.
    *
    * A `Pull.Timed` is not created or intepreted directly, but by
    * calling [[Stream.ToPull.timed]].
    *
    * {{{
    * yourStream.pull.timed(tp => ...).stream
    * }}}
    *
    * The argument to `timed` is a `Pull.Timed[F, O] => Pull[F, O2, R]`
    * function, which describes the pulling logic and is often recursive,
    * with shape:
    *
    * {{{
    * def go(timedPull: Pull.Timed[F, A]): Pull[F, B, Unit] =
    *   timedPull.uncons.flatMap {
    *     case Some((Right(chunk), next)) => doSomething >> go(next)
    *     case Some((Left(_), next)) => doSomethingElse >> go(next)
    *     case None => Pull.done
    *   }
    * }}}
    *
    * Where `doSomething` and `doSomethingElse` are `Pull` computations
    * such as `Pull.output`, in addition to `Pull.Timed.timeout`.
    *
    * See below for detailed descriptions of `timeout` and `uncons`, and
    * look at the [[Stream.ToPull.timed]] scaladoc for an example of usage.
    */
  trait Timed[F[_], O] {
    type Timeout

    /** Waits for either a chunk of elements to be available in the
      * source stream, or a timeout to trigger. Whichever happens
      * first is provided as the resource of the returned pull,
      * alongside a new timed pull that can be used for awaiting
      * again. A `None` is returned as the resource of the pull upon
      * reaching the end of the stream.
      *
      * Receiving a timeout is not a fatal event: the evaluation of the
      * current chunk is not interrupted, and the next timed pull is
      * still returned for further iteration. The lifetime of timeouts
      * is handled by explicit calls to the `timeout` method: `uncons`
      * does not start, restart or cancel any timeouts.
      *
      * Note that the type of timeouts is existential in `Pull.Timed`
      * (hidden, basically) so you cannot do anything on it except for
      * pattern matching, which is best done as a `Left(_)` case.
      */
    def uncons: Pull[F, INothing, Option[(Either[Timeout, Chunk[O]], Pull.Timed[F, O])]]

    /** Asynchronously starts a timeout that will be received by
      * `uncons` after `t`, and immediately returns.
      *
      * Timeouts are resettable: if `timeout` executes whilst a
      * previous timeout is pending, it will cancel it before starting
      * the new one, so that there is at most one timeout in flight at
      * any given time. The implementation guards against stale
      * timeouts: after resetting a timeout, a subsequent `uncons` is
      * guaranteed to never receive an old one.
      *
      * Timeouts can be reset to any `t`, longer or shorter than the
      * previous timeout, but a duration of 0 is treated specially, in
      * that it will cancel a pending timeout but not start a new one.
      *
      * Note: the very first execution of `timeout` does not start
      * running until the first call to `uncons`, but subsequent calls
      * proceed independently after that.
      */
    def timeout(t: FiniteDuration): Pull[F, INothing, Unit]
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
   *  - A Terminal - the end result of pulling. This may have ended in:
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

  /** A Terminal, or terminal, indicates how a pull or Free evaluation ended.
    * A Pull may have succeeded with a result, failed with an exception,
    * or interrupted from another concurrent pull.
    */
  private sealed abstract class Terminal[+R]
      extends Pull[Pure, INothing, R]
      with ViewL[Pure, INothing]

  private def resultEither[R](either: Either[Throwable, R]): Terminal[R] =
    either.fold(Fail(_), Succeeded(_))

  private final case class Succeeded[+R](r: R) extends Terminal[R] {
    override def map[R2](f: R => R2): Terminal[R2] =
      try Succeeded(f(r))
      catch { case NonFatal(err) => Fail(err) }
  }

  private final case class Fail(error: Throwable) extends Terminal[INothing] {
    override def map[R](f: INothing => R): Terminal[R] = this
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
  private final case class Interrupted(context: Unique.Token, deferredError: Option[Throwable])
      extends Terminal[INothing] {
    override def map[R](f: INothing => R): Terminal[R] = this
  }

  private abstract class Bind[+F[_], +O, X, +R](val step: Pull[F, O, X]) extends Pull[F, O, R] {
    def cont(r: Terminal[X]): Pull[F, O, R]
    def delegate: Bind[F, O, X, R] = this
  }

  /** Unrolled view of a `Pull` structure. may be `Terminal` or `EvalBind`
    */
  private sealed trait ViewL[+F[_], +O]

  private sealed abstract case class View[+F[_], +O, X](step: Action[F, O, X])
      extends ViewL[F, O]
      with (Terminal[X] => Pull[F, O, Unit]) {
    def apply(r: Terminal[X]): Pull[F, O, Unit]
  }
  private final class EvalView[+F[_], +O](step: Action[F, O, Unit]) extends View[F, O, Unit](step) {
    def apply(r: Terminal[Unit]): Pull[F, O, Unit] = r
  }

  private final class BindView[+F[_], +O, Y](step: Action[F, O, Y], val b: Bind[F, O, Y, Unit])
      extends View[F, O, Y](step) {
    def apply(r: Terminal[Y]): Pull[F, O, Unit] = b.cont(r)
  }

  private class BindBind[F[_], O, X, Y](
      bb: Bind[F, O, X, Y],
      delegate: Bind[F, O, Y, Unit]
  ) extends Bind[F, O, X, Unit](bb.step) { self =>

    def cont(zr: Terminal[X]): Pull[F, O, Unit] =
      new Bind[F, O, Y, Unit](bb.cont(zr)) {
        override val delegate: Bind[F, O, Y, Unit] = self.delegate
        def cont(yr: Terminal[Y]): Pull[F, O, Unit] = delegate.cont(yr)
      }

  }

  /* unrolled view of Pull `bind` structure * */
  private def viewL[F[_], O](stream: Pull[F, O, Unit]): ViewL[F, O] = {

    @tailrec
    def mk(free: Pull[F, O, Unit]): ViewL[F, O] =
      free match {
        case r: Terminal[Unit]     => r
        case e: Action[F, O, Unit] => new EvalView[F, O](e)
        case b: Bind[F, O, y, Unit] =>
          b.step match {
            case e: Action[F, O, y2] => new BindView(e, b)
            case r: Terminal[_]      => mk(b.cont(r))
            case c: Bind[F, O, x, _] => mk(new BindBind[F, O, x, y](c, b.delegate))
          }
      }

    mk(stream)
  }

  /* An Action is an atomic instruction that can perform effects in `F`
   * to generate by-product outputs of type `O`.
   *
   * Each operation also generates an output of type `R` that is used
   * as control information for the rest of the interpretation or compilation.
   */
  private abstract class Action[+F[_], +O, +R] extends Pull[F, O, R]

  /* A Pull Action to emit a non-empty chunk of outputs */
  private final case class Output[+O](values: Chunk[O]) extends Action[Pure, O, Unit]

  /* A translation point, that wraps an inner stream written in another effect
   */
  private final case class Translate[G[_], F[_], +O](
      stream: Pull[G, O, Unit],
      fk: G ~> F
  ) extends Action[F, O, Unit]

  private final case class MapOutput[+F[_], O, +P](
      stream: Pull[F, O, Unit],
      fun: O => P
  ) extends Action[F, P, Unit]

  private final case class FlatMapOutput[+F[_], O, +P](
      stream: Pull[F, O, Unit],
      fun: O => Pull[F, P, Unit]
  ) extends Action[F, P, Unit]

  /** Steps through the given inner stream, until the first `Output` is reached.
    * It returns the possible `uncons`.
    * Yields to head in form of chunk, then id of the scope that was active after step evaluated and tail of the `stream`.
    *
    * @param stream             Stream to step
    */
  private final case class Uncons[+F[_], +O](stream: Pull[F, O, Unit])
      extends Action[Pure, INothing, Option[(Chunk[O], Pull[F, O, Unit])]]

  /** Steps through the stream, providing a `stepLeg`.
    * Yields to head in form of chunk, then id of the scope that was active after step evaluated and tail of the `stream`.
    *
    * @param stream             Stream to step
    * @param scopeId            scope has to be changed before this step is evaluated, id of the scope must be supplied
    */
  private final case class StepLeg[+F[_], O](stream: Pull[F, O, Unit], scope: Unique.Token)
      extends Action[Pure, INothing, Option[Stream.StepLeg[F, O]]]

  /* The `AlgEffect` trait is for operations on the `F` effect that create no `O` output.
   * They are related to resources and scopes. */
  private sealed abstract class AlgEffect[+F[_], R] extends Action[F, INothing, R]

  private final case class Eval[+F[_], R](value: F[R]) extends AlgEffect[F, R]

  private final case class Acquire[F[_], R](
      resource: F[R],
      release: (R, ExitCase) => F[Unit],
      cancelable: Boolean
  ) extends AlgEffect[F, R]

  private final case class InScope[+F[_], +O](
      stream: Pull[F, O, Unit],
      useInterruption: Boolean
  ) extends Action[F, O, Unit]

  private final case class InterruptWhen[+F[_]](haltOnSignal: F[Either[Throwable, Unit]])
      extends AlgEffect[F, Unit]

  // `InterruptedScope` contains id of the scope currently being interrupted
  // together with any errors accumulated during interruption process
  private final case class CloseScope(
      scopeId: Unique.Token,
      interruption: Option[Interrupted],
      exitCase: ExitCase
  ) extends AlgEffect[Pure, Unit]

  private final case class GetScope[F[_]]() extends AlgEffect[Pure, Scope[F]]

  private[fs2] def stepLeg[F[_], O](
      leg: Stream.StepLeg[F, O]
  ): Pull[F, Nothing, Option[Stream.StepLeg[F, O]]] =
    StepLeg[F, O](leg.next, leg.scopeId)

  /** Wraps supplied pull in new scope, that will be opened before this pull is evaluated
    * and closed once this pull either finishes its evaluation or when it fails.
    */
  private[fs2] def scope[F[_], O](s: Pull[F, O, Unit]): Pull[F, O, Unit] = InScope(s, false)

  /** Like `scope` but allows this scope to be interrupted.
    * Note that this may fail with `Interrupted` when interruption occurred
    */
  private[fs2] def interruptScope[F[_], O](s: Pull[F, O, Unit]): Pull[F, O, Unit] = InScope(s, true)

  private[fs2] def interruptWhen[F[_], O](
      haltOnSignal: F[Either[Throwable, Unit]]
  ): Pull[F, O, Unit] = InterruptWhen(haltOnSignal)

  /* Pull transformation that takes the given stream (pull), unrolls it until it either:
   * - Reaches the end of the stream, and returns None; or
   * - Reaches an Output action, and emits Some pair with
   *   the non-empty chunk of values and the rest of the stream.
   */
  private[fs2] def uncons[F[_], O](
      s: Pull[F, O, Unit]
  ): Pull[F, INothing, Option[(Chunk[O], Pull[F, O, Unit])]] =
    Uncons(s)

  private type Cont[-Y, +G[_], +X] = Terminal[Y] => Pull[G, X, Unit]

  /* Left-folds the output of a stream.
   *
   * Interruption of the stream is tightly coupled between Pull and Scope.
   * Reason for this is unlike interruption of `F` type (e.g. IO) we need to find
   * recovery point where stream evaluation has to continue in Stream algebra.
   *
   * As such the `Unique.Token` is passed to Interrupted as glue between Pull that allows pass-along
   * the information to correctly compute recovery point after interruption was signalled via `Scope`.
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
      initScope: Scope[F],
      extendLastTopLevelScope: Boolean,
      init: B
  )(foldChunk: (B, Chunk[O]) => B)(implicit
      F: MonadError[F, Throwable]
  ): F[B] = {

    trait Run[G[_], X, End] {
      def done(scope: Scope[F]): End
      def out(head: Chunk[X], scope: Scope[F], tail: Pull[G, X, Unit]): End
      def interrupted(scopeId: Unique.Token, err: Option[Throwable]): End
      def fail(e: Throwable): End
    }

    def go[G[_], X, End](
        scope: Scope[F],
        extendedTopLevelScope: Option[Scope[F]],
        translation: G ~> F,
        endRunner: Run[G, X, F[End]],
        stream: Pull[G, X, Unit]
    ): F[End] = {

      def interruptGuard[Mid](
          scope: Scope[F],
          view: Cont[Nothing, G, X],
          runner: Run[G, X, F[Mid]]
      )(
          next: => F[Mid]
      ): F[Mid] =
        scope.isInterrupted.flatMap {
          case None => next
          case Some(outcome) =>
            val result = outcome match {
              case Outcome.Errored(err)       => Fail(err)
              case Outcome.Canceled()         => Interrupted(scope.id, None)
              case Outcome.Succeeded(scopeId) => Interrupted(scopeId, None)
            }
            go(scope, extendedTopLevelScope, translation, runner, view(result))
        }

      def innerMapOutput[K[_], C, D](stream: Pull[K, C, Unit], fun: C => D): Pull[K, D, Unit] =
        viewL(stream) match {
          case r: Terminal[_] => r.asInstanceOf[Terminal[Unit]]
          case v: View[K, C, x] =>
            val mstep: Pull[K, D, x] = (v.step: Action[K, C, x]) match {
              case o: Output[C] =>
                try Output(o.values.map(fun))
                catch { case NonFatal(t) => Fail(t) }
              case t: Translate[l, k, C] => // k= K
                Translate[l, k, D](innerMapOutput[l, C, D](t.stream, fun), t.fk)
              case s: Uncons[k, _]    => s
              case s: StepLeg[k, _]   => s
              case a: AlgEffect[k, _] => a
              case i: InScope[k, c] =>
                InScope[k, D](innerMapOutput(i.stream, fun), i.useInterruption)
              case m: MapOutput[k, b, c] => innerMapOutput(m.stream, fun.compose(m.fun))

              case fm: FlatMapOutput[k, b, c] =>
                // end result: a Pull[K, D, x]
                val innerCont: b => Pull[k, D, Unit] =
                  (x: b) => innerMapOutput[k, c, D](fm.fun(x), fun)
                FlatMapOutput[k, b, D](fm.stream, innerCont)
            }
            new Bind[K, D, x, Unit](mstep) {
              def cont(r: Terminal[x]) = innerMapOutput(v(r), fun)
            }
        }

      def goErr(err: Throwable, view: Cont[Nothing, G, X]): F[End] =
        go(scope, extendedTopLevelScope, translation, endRunner, view(Fail(err)))

      class ViewRunner(val view: Cont[Unit, G, X]) extends Run[G, X, F[End]] {
        private val prevRunner = endRunner

        def done(doneScope: Scope[F]): F[End] =
          go(doneScope, extendedTopLevelScope, translation, endRunner, view(unit))

        def out(head: Chunk[X], scope: Scope[F], tail: Pull[G, X, Unit]): F[End] = {
          @tailrec
          def outLoop(acc: Pull[G, X, Unit], pred: Run[G, X, F[End]]): F[End] =
            // bit of an ugly hack to avoid a stack overflow when these accummulate
            pred match {
              case vrun: ViewRunner =>
                val nacc = new Bind[G, X, Unit, Unit](acc) {
                  def cont(r: Terminal[Unit]) = vrun.view(r)
                }
                outLoop(nacc, vrun.prevRunner)
              case _ => pred.out(head, scope, acc)
            }
          outLoop(tail, this)
        }

        def interrupted(tok: Unique.Token, err: Option[Throwable]): F[End] = {
          val next = view(Interrupted(tok, err))
          go(scope, extendedTopLevelScope, translation, endRunner, next)
        }

        def fail(e: Throwable): F[End] = goErr(e, view)
      }

      def goMapOutput[Z](mout: MapOutput[G, Z, X], view: Cont[Unit, G, X]): F[End] = {
        val mo: Pull[G, X, Unit] = innerMapOutput[G, Z, X](mout.stream, mout.fun)
        go(scope, extendedTopLevelScope, translation, new ViewRunner(view), mo)
      }

      abstract class StepRunR[Y, S](view: Cont[Option[S], G, X]) extends Run[G, Y, F[End]] {
        def done(scope: Scope[F]): F[End] =
          interruptGuard(scope, view, endRunner) {
            go(scope, extendedTopLevelScope, translation, endRunner, view(Succeeded(None)))
          }

        def interrupted(scopeId: Unique.Token, err: Option[Throwable]): F[End] = {
          val next = view(Interrupted(scopeId, err))
          go(scope, extendedTopLevelScope, translation, endRunner, next)
        }

        def fail(e: Throwable): F[End] = goErr(e, view)
      }

      class UnconsRunR[Y](view: Cont[Option[(Chunk[Y], Pull[G, Y, Unit])], G, X])
          extends StepRunR[Y, (Chunk[Y], Pull[G, Y, Unit])](view) {

        def out(head: Chunk[Y], outScope: Scope[F], tail: Pull[G, Y, Unit]): F[End] =
          // For a Uncons, we continue in same Scope at which we ended compilation of inner stream
          interruptGuard(outScope, view, endRunner) {
            val result = Succeeded(Some((head, tail)))
            go(outScope, extendedTopLevelScope, translation, endRunner, view(result))
          }
      }

      class StepLegRunR[Y](view: Cont[Option[Stream.StepLeg[G, Y]], G, X])
          extends StepRunR[Y, Stream.StepLeg[G, Y]](view) {

        def out(head: Chunk[Y], outScope: Scope[F], tail: Pull[G, Y, Unit]): F[End] =
          // StepLeg: we shift back to the scope at which we were
          // before we started to interpret the Leg's inner stream.
          interruptGuard(scope, view, endRunner) {
            val result = Succeeded(Some(new Stream.StepLeg(head, outScope.id, tail)))
            go(scope, extendedTopLevelScope, translation, endRunner, view(result))
          }
      }

      def goFlatMapOut[Y](fmout: FlatMapOutput[G, Y, X], view: View[G, X, Unit]): F[End] = {
        val stepRunR = new FlatMapR(view, fmout.fun)
        // The F.unit is needed because otherwise an stack overflow occurs.
        F.unit >> go(scope, extendedTopLevelScope, translation, stepRunR, fmout.stream)
      }

      class FlatMapR[Y](outView: View[G, X, Unit], fun: Y => Pull[G, X, Unit])
          extends Run[G, Y, F[End]] {
        private[this] def unconsed(chunk: Chunk[Y], tail: Pull[G, Y, Unit]): Pull[G, X, Unit] =
          if (chunk.size == 1 && tail.isInstanceOf[Succeeded[_]])
            // nb: If tl is Pure, there's no need to propagate flatMap through the tail. Hence, we
            // check if hd has only a single element, and if so, process it directly instead of folding.
            // This allows recursive infinite streams of the form `def s: Stream[Pure,O] = Stream(o).flatMap { _ => s }`
            try fun(chunk(0))
            catch { case NonFatal(e) => Fail(e) }
          else {
            def go(idx: Int): Pull[G, X, Unit] =
              if (idx == chunk.size)
                FlatMapOutput[G, Y, X](tail, fun)
              else {
                try transformWith(fun(chunk(idx))) {
                  case Succeeded(_) => go(idx + 1)
                  case Fail(err)    => Fail(err)
                  case interruption @ Interrupted(_, _) =>
                    FlatMapOutput[G, Y, X](interruptBoundary(tail, interruption), fun)
                } catch { case NonFatal(e) => Fail(e) }
              }

            go(0)
          }

        def done(scope: Scope[F]): F[End] =
          interruptGuard(scope, outView, endRunner) {
            go(scope, extendedTopLevelScope, translation, endRunner, outView(unit))
          }

        def out(head: Chunk[Y], outScope: Scope[F], tail: Pull[G, Y, Unit]): F[End] =
          interruptGuard(outScope, outView, endRunner) {
            val fmoc = unconsed(head, tail)
            val next = outView match {
              case ev: EvalView[G, X] => fmoc
              case bv: BindView[G, X, Unit] =>
                val del = bv.b.asInstanceOf[Bind[G, X, Unit, Unit]].delegate
                new Bind[G, X, Unit, Unit](fmoc) {
                  override val delegate: Bind[G, X, Unit, Unit] = del
                  def cont(yr: Terminal[Unit]): Pull[G, X, Unit] = delegate.cont(yr)
                }
            }

            go(outScope, extendedTopLevelScope, translation, endRunner, next)
          }

        def interrupted(scopeId: Unique.Token, err: Option[Throwable]): F[End] = {
          val next = outView(Interrupted(scopeId, err))
          go(scope, extendedTopLevelScope, translation, endRunner, next)
        }

        def fail(e: Throwable): F[End] = goErr(e, outView)
      }

      def goEval[V](eval: Eval[G, V], view: Cont[V, G, X]): F[End] =
        scope.interruptibleEval(translation(eval.value)).flatMap { eitherOutcome =>
          val result = eitherOutcome match {
            case Right(r)                       => Succeeded(r)
            case Left(Outcome.Errored(err))     => Fail(err)
            case Left(Outcome.Canceled())       => Interrupted(scope.id, None)
            case Left(Outcome.Succeeded(token)) => Interrupted(token, None)
          }
          go(scope, extendedTopLevelScope, translation, endRunner, view(result))
        }

      def goAcquire[R](acquire: Acquire[G, R], view: Cont[R, G, X]): F[End] = {
        val onScope = scope.acquireResource[R](
          poll =>
            if (acquire.cancelable) poll(translation(acquire.resource))
            else translation(acquire.resource),
          (resource, exit) => translation(acquire.release(resource, exit))
        )

        val cont = onScope.flatMap { outcome =>
          val result = outcome match {
            case Outcome.Succeeded(Right(r))      => Succeeded(r)
            case Outcome.Succeeded(Left(scopeId)) => Interrupted(scopeId, None)
            case Outcome.Canceled()               => Interrupted(scope.id, None)
            case Outcome.Errored(err)             => Fail(err)
          }
          go(scope, extendedTopLevelScope, translation, endRunner, view(result))
        }
        interruptGuard(scope, view, endRunner)(cont)
      }

      def goInterruptWhen(
          haltOnSignal: F[Either[Throwable, Unit]],
          view: Cont[Unit, G, X]
      ): F[End] = {
        val onScope = scope.acquireResource(
          _ => scope.interruptWhen(haltOnSignal),
          (f: Fiber[F, Throwable, Unit], _: ExitCase) => f.cancel
        )
        val cont = onScope.flatMap { outcome =>
          val result = outcome match {
            case Outcome.Succeeded(Right(_))      => Succeeded(())
            case Outcome.Succeeded(Left(scopeId)) => Interrupted(scopeId, None)
            case Outcome.Canceled()               => Interrupted(scope.id, None)
            case Outcome.Errored(err)             => Fail(err)
          }
          go(scope, extendedTopLevelScope, translation, endRunner, view(result))
        }
        interruptGuard(scope, view, endRunner)(cont)
      }

      def goInScope(
          stream: Pull[G, X, Unit],
          useInterruption: Boolean,
          view: Cont[Unit, G, X]
      ): F[End] = {
        def boundToScope(scopeId: Unique.Token): Pull[G, X, Unit] =
          new Bind[G, X, Unit, Unit](stream) {
            def cont(r: Terminal[Unit]) = r match {
              case Succeeded(_) =>
                CloseScope(scopeId, None, ExitCase.Succeeded)
              case interrupted @ Interrupted(_, _) =>
                CloseScope(scopeId, Some(interrupted), ExitCase.Canceled)
              case Fail(err) =>
                transformWith(CloseScope(scopeId, None, ExitCase.Errored(err))) {
                  case Succeeded(_) => Fail(err)
                  case Fail(err0)   => Fail(CompositeFailure(err, err0, Nil))
                  case Interrupted(interruptedScopeId, _) =>
                    sys.error(
                      s"Impossible, cannot interrupt when closing failed scope: $scopeId, $interruptedScopeId, $err"
                    )
                }
            }
          }
        val maybeCloseExtendedScope: F[Option[Scope[F]]] =
          // If we're opening a new top-level scope (aka, direct descendant of root),
          // close the current extended top-level scope if it is defined.
          if (scope.isRoot && extendedTopLevelScope.isDefined)
            extendedTopLevelScope.traverse_(_.close(ExitCase.Succeeded).rethrow).as(None)
          else
            F.pure(extendedTopLevelScope)

        val vrun = new ViewRunner(view)
        val tail = maybeCloseExtendedScope.flatMap { newExtendedScope =>
          scope.open(useInterruption).rethrow.flatMap { childScope =>
            go(childScope, newExtendedScope, translation, vrun, boundToScope(childScope.id))
          }
        }
        interruptGuard(scope, view, vrun)(tail)
      }

      def goCloseScope(close: CloseScope, view: Cont[Unit, G, X]): F[End] = {
        def closeTerminal(r: Either[Throwable, Unit], ancestor: Scope[F]): Terminal[Unit] =
          close.interruption match {
            case None => resultEither(r)
            case Some(Interrupted(interruptedScopeId, err)) =>
              def err1 = CompositeFailure.fromList(r.swap.toOption.toList ++ err.toList)
              if (ancestor.descendsFrom(interruptedScopeId))
                // we still have scopes to interrupt, lets build interrupted tail
                Interrupted(interruptedScopeId, err1)
              else
                // interrupts scope was already interrupted, resume operation
                err1 match {
                  case None      => unit
                  case Some(err) => Fail(err)
                }
          }

        scope.findInLineage(close.scopeId).flatMap {
          case Some(toClose) if toClose.isRoot =>
            // Impossible - don't close root scope as a result of a `CloseScope` call
            go(scope, extendedTopLevelScope, translation, endRunner, view(unit))

          case Some(toClose) if extendLastTopLevelScope && toClose.level == 1 =>
            // Request to close the current top-level scope - if we're supposed to extend
            // it instead, leave the scope open and pass it to the continuation
            extendedTopLevelScope.traverse_(_.close(ExitCase.Succeeded).rethrow) *>
              toClose.openAncestor.flatMap { ancestor =>
                go(ancestor, Some(toClose), translation, endRunner, view(unit))
              }

          case Some(toClose) =>
            toClose.close(close.exitCase).flatMap { r =>
              toClose.openAncestor.flatMap { ancestor =>
                val res = closeTerminal(r, ancestor)
                go(ancestor, extendedTopLevelScope, translation, endRunner, view(res))
              }
            }

          case None =>
            // scope already closed, continue with current scope
            val result = close.interruption.getOrElse(unit)
            go(scope, extendedTopLevelScope, translation, endRunner, view(result))
        }
      }

      viewL(stream) match {
        case _: Succeeded[_]  => endRunner.done(scope)
        case failed: Fail     => endRunner.fail(failed.error)
        case int: Interrupted => endRunner.interrupted(int.context, int.deferredError)

        case view: View[G, X, y] =>
          view.step match {
            case output: Output[_] =>
              interruptGuard(scope, view, endRunner)(
                endRunner.out(output.values, scope, view(unit))
              )

            case fmout: FlatMapOutput[g, z, X] => // y = Unit
              goFlatMapOut[z](fmout, view.asInstanceOf[View[g, X, Unit]])

            case tst: Translate[h, g, X] => // y = Unit
              val composed: h ~> F = translation.asInstanceOf[g ~> F].compose[h](tst.fk)

              val translateRunner: Run[h, X, F[End]] = new Run[h, X, F[End]] {
                def done(scope: Scope[F]): F[End] = endRunner.done(scope)
                def out(head: Chunk[X], scope: Scope[F], tail: Pull[h, X, Unit]): F[End] =
                  endRunner.out(head, scope, Translate(tail, tst.fk))
                def interrupted(scopeId: Unique.Token, err: Option[Throwable]): F[End] =
                  endRunner.interrupted(scopeId, err)
                def fail(e: Throwable): F[End] =
                  endRunner.fail(e)
              }
              go[h, X, End](scope, extendedTopLevelScope, composed, translateRunner, tst.stream)

            case u: Uncons[G, y] =>
              val v = view.asInstanceOf[View[G, X, Option[(Chunk[y], Pull[G, y, Unit])]]]
              // a Uncons is run on the same scope, without shifting.
              go(scope, extendedTopLevelScope, translation, new UnconsRunR(v), u.stream)

            case u: StepLeg[G, y] =>
              val v = view.asInstanceOf[View[G, X, Option[Stream.StepLeg[G, y]]]]
              scope
                .shiftScope(u.scope, u.toString)
                .flatMap(go(_, extendedTopLevelScope, translation, new StepLegRunR(v), u.stream))

            case _: GetScope[_] =>
              val result = Succeeded(scope.asInstanceOf[y])
              go(scope, extendedTopLevelScope, translation, endRunner, view(result))

            case mout: MapOutput[g, z, X] => goMapOutput[z](mout, view)
            case eval: Eval[G, r]         => goEval[r](eval, view)
            case acquire: Acquire[G, _]   => goAcquire(acquire, view)
            case inScope: InScope[g, X]   => goInScope(inScope.stream, inScope.useInterruption, view)
            case int: InterruptWhen[g]    => goInterruptWhen(translation(int.haltOnSignal), view)
            case close: CloseScope        => goCloseScope(close, view)
          }
      }
    }

    val initFk: F ~> F = cats.arrow.FunctionK.id[F]

    class OuterRun(accB: B) extends Run[F, O, F[B]] { self =>
      override def done(scope: Scope[F]): F[B] = F.pure(accB)

      override def fail(e: Throwable): F[B] = F.raiseError(e)

      override def interrupted(scopeId: Unique.Token, err: Option[Throwable]): F[B] =
        err.fold(F.pure(accB))(F.raiseError)

      override def out(head: Chunk[O], scope: Scope[F], tail: Pull[F, O, Unit]): F[B] =
        try {
          val nrunner = new OuterRun(foldChunk(accB, head))
          go[F, O, B](scope, None, initFk, nrunner, tail)
        } catch {
          case NonFatal(e) =>
            viewL(tail) match {
              case Succeeded(_) => F.raiseError(e)
              case Fail(e2)     => F.raiseError(CompositeFailure(e2, e))
              case Interrupted(_, err) =>
                F.raiseError(err.fold(e)(t => CompositeFailure(e, t)))
              case v: View[F, O, Unit] =>
                go[F, O, B](scope, None, initFk, self, v(Fail(e)))
            }
        }
    }

    go[F, O, B](initScope, None, initFk, new OuterRun(init), stream)
  }

  /* Inject interruption to the tail used in flatMap.  Assures that close of the scope
   * is invoked if at the flatMap tail, otherwise switches evaluation to `interrupted` path*/
  private[this] def interruptBoundary[F[_], O](
      stream: Pull[F, O, Unit],
      interruption: Interrupted
  ): Pull[F, O, Unit] =
    viewL(stream) match {
      case interrupted: Interrupted => interrupted // impossible
      case _: Succeeded[_]          => interruption
      case failed: Fail =>
        val mixed = CompositeFailure
          .fromList(interruption.deferredError.toList :+ failed.error)
          .getOrElse(failed.error)
        Fail(mixed)

      case view: View[F, O, _] =>
        view.step match {
          case CloseScope(scopeId, _, _) =>
            // Inner scope is getting closed b/c a parent was interrupted
            val cl: Pull[F, O, Unit] = CloseScope(scopeId, Some(interruption), ExitCase.Canceled)
            transformWith(cl)(view)
          case _ =>
            // all other cases insert interruption cause
            view(interruption)
        }
    }

  private[fs2] def flatMapOutput[F[_], F2[x] >: F[x], O, O2](
      p: Pull[F, O, Unit],
      f: O => Pull[F2, O2, Unit]
  ): Pull[F2, O2, Unit] =
    p match {
      case r: Terminal[_]        => r
      case a: AlgEffect[F, Unit] => a
      case _                     => FlatMapOutput(p, f)
    }

  private[fs2] def translate[F[_], G[_], O](
      stream: Pull[F, O, Unit],
      fK: F ~> G
  ): Pull[G, O, Unit] =
    stream match {
      case r: Terminal[_] => r
      case t: Translate[e, f, _] =>
        translate[e, G, O](t.stream, t.fk.andThen(fK.asInstanceOf[f ~> G]))
      case o: Output[_] => o
      case _            => Translate(stream, fK)
    }

  /* Applies the outputs of this pull to `f` and returns the result in a new `Pull`. */
  private[fs2] def mapOutput[F[_], O, P](
      stream: Pull[F, O, Unit],
      fun: O => P
  ): Pull[F, P, Unit] =
    stream match {
      case r: Terminal[_]        => r
      case a: AlgEffect[F, _]    => a
      case t: Translate[g, f, _] => Translate[g, f, P](mapOutput(t.stream, fun), t.fk)
      case m: MapOutput[f, q, o] => MapOutput(m.stream, fun.compose(m.fun))
      case _                     => MapOutput(stream, fun)
    }

  private[this] def transformWith[F[_], O, R, S](p: Pull[F, O, R])(
      f: Terminal[R] => Pull[F, O, S]
  ): Pull[F, O, S] =
    new Bind[F, O, R, S](p) {
      def cont(r: Terminal[R]): Pull[F, O, S] =
        try f(r)
        catch { case NonFatal(e) => Fail(e) }
    }

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
    with Sync[Pull[F, O, *]]
    with MonadCancel.Uncancelable[Pull[F, O, *], Throwable] {
  def monotonic: Pull[F, O, FiniteDuration] = Pull.eval(F.monotonic)
  def realTime: Pull[F, O, FiniteDuration] = Pull.eval(F.realTime)
  def suspend[A](hint: Sync.Type)(thunk: => A): Pull[F, O, A] = Pull.eval(F.suspend(hint)(thunk))
  def forceR[A, B](fa: Pull[F, O, A])(fb: Pull[F, O, B]): Pull[F, O, B] =
    flatMap(attempt(fa))(_ => fb)
}
