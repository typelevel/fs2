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

import scala.annotation.{nowarn, tailrec}
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

import cats.data.AndThen
import cats.{Eval => _, _}
import cats.effect.kernel._
import cats.syntax.all._

import fs2.internal._
import Resource.ExitCase
import Pull._

/** A purely functional data structure that describes a process. This process
  * may evaluate actions in an effect type F, emit any number of output values
  * of type O (or None), and may a) terminate with a single result of type R;
  * or b) terminate abnormally by raising (inside the effect `F`) an exception,
  * or c) terminate because it was cancelled by another process,
  * or d) not terminate.
  *
  * Like types from other effect libraries, pulls are pure and immutable values.
  * They preserve referential transparency.
  *
  * === Chunking ===
  *
  * The output values of a pull are emitted not one by one, but in chunks.
  * A `Chunk` is an immutable sequence with constant-time indexed lookup. For example,
  * a pull `p: Pull[F, Byte, R]` internally operates and emits `Chunk[Byte]`
  * values, which can wrap unboxed byte arrays -- avoiding boxing/unboxing costs.
  * The `Pull` API provides mechanisms for working at both the chunk level and
  * the individual element level. Generally, working at the chunk level will
  * result in better performance but at the cost of more complex implementations
  *
  * A pull only emits non-empty chunks.
  *
  * However, chunks are not merely an operational matter of efficiency. Each
  * pull is emitted from a chunk atomically, which is to say, any errors or
  * interruptions in a pull can only happen between chunks, not within a
  * chunk. For instance, if creating a new chunk of values fails (raises an
  * uncaught exception) while creating an intermediate value, then it fails
  * to create the entire chunk and previous values are discarded.
  *
  * === Evaluation ===
  *
  * Like other functional effect types (e.g. `cats.effect.IO`), a pull
  * describes a _process_ or _computation_. It is not a running process nor a
  * handle for the result of a spawned, running process, like `scala.concurrent.Future`.
  *
  * A pull can be converted to a stream and then compiled to an effectful value.
  * For a `Pull[F, O, Unit]`, the result of compilation is a combination, via the
  * monad instance of `F`, of all the actions in the effect `F` present in the pull.
  * The result of that `F` action is the result of combining the outputs emitted by
  * the pull, in the order it emits them, using a _fold_ function. Depending on that
  * function, outputs may be collected into a list (or vector or array or ...),
  * combined together into a single value, or just discarded altogether (by _draining_
  * the pull).
  *
  * Compilation is pull-based, rather than push-based (hence the name of the datatype).
  * It is the compilation process itself, that determines when the evaluation
  * of each single effect can proceed or is held back. Effects and outputs later
  * in the pull are not performed or emitted, _unless and until_ the compilation
  * calls for them.
  *
  * === Resource scoping ===
  *
  * The effects in a `Pull` may operate on resources, which must be retained during
  * the execution of the pull, may be shared by several pulls, and must be
  * properly finalised when no longer needed, regardless of whether the pull completed
  * successfully or not. A pull tracks its resources using '''scopes''', which register
  * how many pulls are actively using each resource, and finalises resources when no
  * longer used.
  *
  * Some operations of the `Pull` API can be used to introduce new resource scopes,
  * or resource boundaries.
  *
  * === Functional typeclasses ===
  *
  * The `Pull` data structure is a "free" implementation of `Monad` and has an instance
  * for `cats.effect.kernel.Sync`.
  *
  * For any types `F[_]` and `O`, a `Pull[F, O, *]` holds the following laws:
  *
  *   - `pure >=> f == f`
  *   - `f >=> pure == f`
  *   - `(f >=> g) >=> h == f >=> (g >=> h)`
  * where `f >=> g` is defined as `a => a flatMap f flatMap g`
  *   - `handleErrorWith(raiseError(e))(f) == f(e)`
  *
  * @tparam F[_] the type of effect that can be performed by this pull.
  *         An effect type of `Nothing`, also known in `fs2` by the alias `Pure`,
  *         indicates that this pull perform no effectful actions.
  *         _Note_: `Nothing` is a polykinded type, so it can also be
  *         applied as an argument to the type parameter `F[_]`.
  * @tparam O The outputs emitted by this Pull. An output type of `Nothing` means
  *           that this pull does not emit any outputs.
  * @tparam R The type of result returned by this Pull upon successful termination.
  *           An output type of `Nothing` indicates that this pull cannot terminate
  *           successfully: it may fail, be cancelled, or never terminate.
  */
sealed abstract class Pull[+F[_], +O, +R] {

  /** Applies the result of this pull to `f` and returns the result.
    *
    * This method returns a new composed pull, which will do as follows:
    *
    * - If `this` pull succeeds with a result `r` of type R, the `f` function
    *   is applied to `r`, to build a new pull `f(r)`, and the result pull
    *   starts running that new pull. The composed pull will terminate (or not)
    *   just as the new pull `f(r)` does.
    * - If `this` pull fails or is interrupted, then the composed pull
    *   terminates with that same failure or interruption.
    * - If evaluating `f(r)` to build the throws an exception, the result
    *   is a pull that fails with that exception.
    *
    * The composed pull emits all outputs emitted by `this` pull,
    * and if successful will start emitting the outputs from the generated pull.
    *
    * This operation does not modify resource scope boundaries. The generated
    * `post` pull starts running on the same scope in which `this` pull ended,
    * and the composed pull will end on the same scope in which `post` pull does.
    */
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

  /** Lazily appends the given `post` pull, to be run after `this` pull.
    *
    * - If `this` pull succeeds, then its result is discarded, the `post`
    *   pull is built, and starts running. The result of `post` is
    *   the result of the composed pull.
    *
    * - If `this` pull raises an error or is interrupted, the `post` argument is
    *   not evaluated and the composed pull ends just as `this` pull did.
    *
    * In both cases, the effectful actions and outputs of the appended pull
    * consists of those outputs and actions from the first pull, followed by
    * those from the `post` pull, in the same order as they would come out of each pull.
    *
    * Since the `post` argument is lazy, this method can be used to build lazy
    * pulls, which are not built in memory until after the prefix has run.
    * This allows defining pulls recursively.
    *
    * This operation does not add or remove any resource scope boundaries.
    * The `post` pull runs on the same scope in which `this` pull ended.
    * The composed pull ends on whatever scope the `post` pull does.
    *
    * This is equivalent to `.flatMap(_ => post)`
    */
  def >>[F2[x] >: F[x], O2 >: O, S](post: => Pull[F2, O2, S]): Pull[F2, O2, S] =
    new Bind[F2, O2, R, S](this) {
      def cont(r: Terminal[R]): Pull[F2, O2, S] =
        r match {
          case _: Succeeded[_] => post
          case r: Interrupted  => r
          case r: Fail         => r
        }
    }

  /** Allows to recover from any error raised by the evaluation of this pull.
    * This method returns a composed pull with the following semantics:
    * - If an error occurs, the supplied function is used to build a new handler
    *   pull, and it starts running it. However, the pull cannot be resumed from
    *   the point at which the error arose.
    * - If no error is raised, the resulting  pull just does what `this` pull does.
    */
  def handleErrorWith[F2[x] >: F[x], O2 >: O, R2 >: R](
      handler: Throwable => Pull[F2, O2, R2]
  ): Pull[F2, O2, R2] =
    new Bind[F2, O2, R2, R2](this) {
      def cont(term: Terminal[R2]): Pull[F2, O2, R2] =
        term match {
          case Fail(e) =>
            try handler(e)
            catch { case NonFatal(e) => Fail(e) }
          case other => other
        }
    }

  /** Run `post` after `this`, regardless of errors during `this`:
    *
    * - If `this` pull terminates successfully, then its result is discarded
    *   and the `post` pull is run. However the `post` pull ends, be it in
    *   success, error, interruption, is how the combined pull ends.
    *
    * - If `this` pull fails, the `post` pull is run next. If the `post` pull
    *   ends fails or is interrupted, that is how the combined pull ends.
    *   However, if the `post` pull succeeds, then the combined `onComplete` pull
    *   fails again with the error that was raised from `this` pull.
    *
    * - If `this` pull is interrupted, then the `post` error is never run
    *   and the combined pull ends with that same interruption.
    */
  def onComplete[F2[x] >: F[x], O2 >: O, R2](post: => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    handleErrorWith(e => post >> Fail(e)) >> post

  /** Short-hand for `(this: Pull[F2, P, S])`. Used to assist with type inference. */
  def covaryAll[F2[x] >: F[x], O2 >: O, R2 >: R]: Pull[F2, O2, R2] = this

  /** Short-hand for `(this: Pull[F2, O, R])`. Used to assist with type inference. */
  def covary[F2[x] >: F[x]]: Pull[F2, O, R] = this

  /** Short-hand for `(this: Pull[F, O2, R])`. Used to assist with type inference. */
  def covaryOutput[O2 >: O]: Pull[F, O2, R] = this

  /** Short-hand for `(this: Pull[F, O, R2])`. Used to assist with type inference. */
  def covaryResult[R2 >: R]: Pull[F, O, R2] = this

  /** Returns a pull with the result wrapped in `Right`,
    * or an error wrapped in `Left` if the pull has raised an error.
    * If `this` pull is interrupted, the attempted pull ends the same way.
    */
  def attempt: Pull[F, O, Either[Throwable, R]] =
    map(r => Right(r)).handleErrorWith(t => Succeeded(Left(t)))

  /** Maps the result of this pull with the `f` mapping funciton.
    *
    * If `this` pull ends in success with a result `r`, then the function `f`
    * is applied to its result `r`, and the image `f(r)` is the result of the
    * mapped pull. However, if the evaluation of `f(r)` throws an error, the
    * mapped  pull fails with that error.
    *
    * Note: for some simple cases of Pull, the  `map` function may be eagerly
    * applied, or discarded, _before_ the pull starts being run.
    *
    * If `this` pull terminates abnormally, so does the mapped pull.
    */
  def map[S](f: R => S): Pull[F, O, S] =
    new Bind[F, O, R, S](this) {
      def cont(r: Terminal[R]) = r.map(f)
    }

  /** Discards the result of this pull.
    *
    * If `this` pull ends in success, its result is discarded and the _voided_
    * pull returns the unit `()` value. Otherwise, the voided pull just does
    * the same as `this` pull does.
    *
    * Alias for `this.map(_ => () )`.
    */
  def void: Pull[F, O, Unit] = as(())

  /** Replaces the result of this pull with the given constant value.
    * If `this` pull succeeds, then its result is discarded and the resulting
    * pull succeeds with the `s` value as its result.
    * Otherwise, if `this` pull fails or is interrupted, then the result pull
    * ends the same way.
    *
    * Alias for `_.map(_ => o2)`.
    *
    * @tparam S The type of the constant,
    * @param  s The new result value of the pull
    */
  def as[S](s: S): Pull[F, O, S] = map(_ => s)
}

object Pull extends PullLowPriority {

  implicit final class StreamPullOps[F[_], O](private val self: Pull[F, O, Unit]) extends AnyVal {

    /** Interprets this pull to produce a stream. This method introduces a resource
      * scope, to ensure any resources acquired by this pull are released in due
      * course, even if the resulting stream does not terminate successfully.
      *
      * May only be called on pulls which return a `Unit` result type. Use
      * `p.void.stream` to explicitly ignore the result type of the pull.
      */
    def stream: Stream[F, O] = new Stream(Pull.scope(self))

    /** Interpret this `Pull` to produce a `Stream` without introducing a scope.
      *
      * Only use this if you know a scope is not needed. Scope introduction is
      *
      * generally harmless and the risk of not introducing a scope is a memory leak
      * in streams that otherwise would execute in constant memory.
      *
      * May only be called on pulls whose result type is `Unit`.
      * Use `p.void.stream` to explicitly  ignore the result of a pull.
      */
    def streamNoScope: Stream[F, O] = new Stream(self)
  }

  private[this] val unit: Terminal[Unit] = Succeeded(())

  /** A pull that performs no effects, emits no outputs, and
    * always terminates successfully with a unit result.
    */
  val done: Pull[Pure, INothing, Unit] = unit

  /** Creates an pull that performs no effects, emits no outputs,
    * and terminates successfully with the supplied value as its result.
    */
  def pure[F[_], R](r: R): Pull[F, INothing, R] = Succeeded(r)

  /** Lifts a throwable error into an atomic pull that emits no outputs and
    * fails with the given error, without any result.
    *
    * The `F` type must be explicitly provided (e.g., via `raiseError[IO]`
    * or `raiseError[Fallible]`).
    */
  @nowarn("cat=unused-params")
  def raiseError[F[_]: RaiseThrowable](err: Throwable): Pull[F, INothing, INothing] = Fail(err)

  /** Creates a pull that evaluates the supplied effect `fr`, emits no
    * outputs, and terminates with the result of the effect.
    * If the `fr` effect fails with an error, the new pull fails with that error.
    */
  def eval[F[_], R](fr: F[R]): Pull[F, INothing, R] = Eval[F, R](fr)

  /** Lifts the given output value `O` into a pull that performs no
    * effects, emits that single output in a singleton chunk, and always
    * terminates successfully with a unit result.
    *
    * _Note_: using singleton chunks is not efficient. If possible,
    * use the chunk-based `output` method instead.
    */
  def output1[F[x] >: Pure[x], O](o: O): Pull[F, O, Unit] = Output(Chunk.singleton(o))

  /** Creates a pull that emits the elements of the given chunk.
    * The new pull performs no effects and terminates successfully with a unit result.
    */
  def output[F[x] >: Pure[x], O](os: Chunk[O]): Pull[Pure, O, Unit] =
    if (os.isEmpty) Pull.done else Output[O](os)

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

  /** Like [[eval]] but if the effectful value fails, the exception is returned
    * in a `Left` instead of failing the pull.
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

  /** Extends the scope of the currently open resources to the specified stream,
    * preventing them from being finalized until after `s` completes execution,
    * even if the returned pull is converted to a stream, compiled, and
    * evaluated before `s` is compiled and evaluated.
    */
  def extendScopeTo[F[_], O](
      s: Stream[F, O]
  )(implicit F: MonadError[F, Throwable]): Pull[F, INothing, Stream[F, O]] =
    for {
      scope <- Pull.getScope[F]
      lease <- Pull.eval(scope.lease)
    } yield s.onFinalize(lease.cancel.redeemWith(F.raiseError(_), _ => F.unit))

  /** Repeatedly uses the output of the pull as input for the next step of the
    * pull. Halts when a step terminates with `None` or `Pull.raiseError`.
    */
  def loop[F[_], O, R](f: R => Pull[F, O, Option[R]]): R => Pull[F, O, Unit] =
    (r: R) =>
      f(r).flatMap {
        case None    => Pull.done
        case Some(s) => loop(f)(s)
      }

  /** Intantiates with a state. Repeatedly uses the left value of the result of
    * the pull as input for the next step. The Pull terminates when a step terminates with
    * `Right` or `Pull.raiseError`.
    */
  def loopEither[F[_], O, S, R](f: S => Pull[F, O, Either[S, R]]): S => Pull[F, O, R] =
    (s: S) =>
      f(s).flatMap {
        case Left(ns) => loopEither(f)(ns)
        case Right(r) => Pull.pure(r)
      }

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

  /** Returns a pull that evaluates the supplied by-name each time the pull is
    * used, allowing use of a mutable value in pull computations.
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

  /* A Terminal indicates how a pull evaluation ended.
   * A pull may have succeeded with a result, failed with an exception,
   * or interrupted from another concurrent pull.
   */
  private sealed abstract class Terminal[+R]
      extends Pull[Pure, INothing, R]
      with ViewL[Pure, INothing]

  private final case class Succeeded[+R](r: R) extends Terminal[R] {
    override def map[R2](f: R => R2): Terminal[R2] =
      try Succeeded(f(r))
      catch { case NonFatal(err) => Fail(err) }
  }

  private final case class Fail(error: Throwable) extends Terminal[INothing] {
    override def map[R](f: INothing => R): Terminal[R] = this
  }

  /** Signals that pull evaluation was interrupted.
    *
    * @param context Any user specific context that needs to be captured
    *                during interruption for eventual resume of the operation.
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

  /* Unrolled view of a `Pull` structure. */
  private sealed trait ViewL[+F[_], +O]

  private sealed abstract case class View[+F[_], +O, X](step: Action[F, O, X])
      extends ViewL[F, O]
      with (Terminal[X] => Pull[F, O, Unit])

  private final class EvalView[+F[_], +O](step: Action[F, O, Unit]) extends View[F, O, Unit](step) {
    def apply(r: Terminal[Unit]): Pull[F, O, Unit] = r
  }

  private final class BindView[+F[_], +O, Y](step: Action[F, O, Y], val b: Bind[F, O, Y, Unit])
      extends View[F, O, Y](step) {
    def apply(r: Terminal[Y]): Pull[F, O, Unit] = b.cont(r)
  }

  // This class is not created by the combinators in the public Pull API, only during compilation
  private class DelegateBind[F[_], O, Y](
      step: Pull[F, O, Y],
      override val delegate: Bind[F, O, Y, Unit]
  ) extends Bind[F, O, Y, Unit](step) {
    def cont(yr: Terminal[Y]): Pull[F, O, Unit] = delegate.cont(yr)
  }

  // This class is not created by combinators in public Pull API, only during compilation
  private class BindBind[F[_], O, X, Y](
      step: Pull[F, O, X],
      bb: Bind[F, O, X, Y],
      del: Bind[F, O, Y, Unit]
  ) extends Bind[F, O, X, Unit](step) {
    def cont(zr: Terminal[X]): Pull[F, O, Unit] =
      new DelegateBind(bb.cont(zr), del)
  }

  private def viewL[F[_], O](stream: Pull[F, O, Unit]): ViewL[F, O] = {

    @tailrec
    def mk(free: Pull[F, O, Unit]): ViewL[F, O] =
      free match {
        case e: Action[F, O, Unit] => new EvalView[F, O](e)
        case b: Bind[F, O, y, Unit] =>
          b.step match {
            case c: Bind[F, O, x, _] => mk(new BindBind[F, O, x, y](c.step, c.delegate, b.delegate))
            case e: Action[F, O, y2] => new BindView(e, b.delegate)
            case r: Terminal[_]      => mk(b.cont(r))
          }
        case r: Terminal[Unit] => r
      }

    mk(stream)
  }

  /* An action is an instruction that can perform effects in `F`
   * to generate by-product outputs of type `O`.
   *
   * Each operation also generates an output of type `R` that is used
   * as control information for the rest of the interpretation or compilation.
   */
  private abstract class Action[+F[_], +O, +R] extends Pull[F, O, R]

  /* An action that emits a non-empty chunk of outputs. */
  private final case class Output[+O](values: Chunk[O]) extends Action[Pure, O, Unit]

  /* A translation point, that wraps an inner stream written in another effect. */
  private final case class Translate[G[_], F[_], +O](
      stream: Pull[G, O, Unit],
      fk: G ~> F
  ) extends Action[F, O, Unit]

  private final case class MapOutput[+F[_], O, +P](
      stream: Pull[F, O, Unit],
      fun: AndThen[O, P]
  ) extends Action[F, P, Unit]

  private final case class FlatMapOutput[+F[_], O, +P](
      stream: Pull[F, O, Unit],
      fun: O => Pull[F, P, Unit]
  ) extends Action[F, P, Unit]

  /* Steps through the given inner stream, until the first `Output` is reached.
   * It returns the possible `uncons`.
   * Yields to head in form of chunk, then id of the scope that was active
   * after step evaluated and tail of the `stream`.
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
  private final case class StepLeg[+F[_], +O](stream: Pull[F, O, Unit], scope: Unique.Token)
      extends Action[Pure, INothing, Option[Stream.StepLeg[F, O]]]

  /* The `AlgEffect` trait is for operations on the `F` effect that create no `O` output. */
  private sealed abstract class AlgEffect[+F[_], R] extends Action[F, INothing, R]

  private final case class Eval[+F[_], R](value: F[R]) extends AlgEffect[F, R]

  private final case class Acquire[+F[_], R](
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
  private abstract class CloseScope extends AlgEffect[Pure, Unit] {
    def scopeId: Unique.Token
    def interruption: Option[Interrupted]
    def exitCase: ExitCase
  }

  private final case class SucceedScope(scopeId: Unique.Token) extends CloseScope {
    def exitCase: ExitCase = ExitCase.Succeeded
    def interruption: Option[Interrupted] = None
  }

  private final case class CanceledScope(scopeId: Unique.Token, inter: Interrupted)
      extends CloseScope {
    def exitCase: ExitCase = ExitCase.Canceled
    def interruption: Option[Interrupted] = Some(inter)
  }

  private final case class FailedScope(scopeId: Unique.Token, err: Throwable) extends CloseScope {
    def exitCase: ExitCase = ExitCase.Errored(err)
    def interruption: Option[Interrupted] = None
  }

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

  /* Left-folds the output of a stream in to a single value of type `B`.
   *
   * === Interruption ===
   *
   * Interruption of the stream is implemented cooperatively between `Pull` and `Scope`.
   * Unlike interruption of an `F[_]: MonadCancelThrow` type (e.g. `IO`), stream interruption
   * needs to find the recovery point where stream evaluation continues.
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

    type CallRun[G[_], X, End] = Run[G, X, End] => End

    class BuildR[G[_], X, End] extends Run[G, X, F[CallRun[G, X, F[End]]]] {
      def fail(e: Throwable) = F.raiseError(e)

      def done(scope: Scope[F]) =
        F.pure((cont: Run[G, X, F[End]]) => cont.done(scope))
      def out(head: Chunk[X], scope: Scope[F], tail: Pull[G, X, Unit]) =
        F.pure((cont: Run[G, X, F[End]]) => cont.out(head, scope, tail))
      def interrupted(scopeId: Unique.Token, err: Option[Throwable]) =
        F.pure((cont: Run[G, X, F[End]]) => cont.interrupted(scopeId, err))
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
              case o: Output[_] =>
                try Output(o.values.map(fun))
                catch { case NonFatal(t) => Fail(t) }
              case t: Translate[l, k, _] => // k= K
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
        val inner = go(scope, extendedTopLevelScope, translation, new BuildR[G, X, End], mo)
        inner.attempt.flatMap(_.fold(goErr(_, view), _.apply(new ViewRunner(view))))
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

      def goFlatMapOut[Y](fmout: FlatMapOutput[G, Y, X], view: Cont[Unit, G, X]): F[End] = {
        val runr = new BuildR[G, Y, End]
        val flatMapR = new FlatMapR(view, fmout.fun)

        // The F.unit is needed because otherwise an stack overflow occurs.
        F.unit >>
          go(scope, extendedTopLevelScope, translation, runr, fmout.stream).attempt
            .flatMap(_.fold(goErr(_, view), _.apply(flatMapR)))
      }

      class FlatMapR[Y](outView: Cont[Unit, G, X], fun: Y => Pull[G, X, Unit])
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

        def out(head: Chunk[Y], outScope: Scope[F], tail: Pull[G, Y, Unit]): F[End] = {
          val fmoc = unconsed(head, tail)
          val next = outView match {
            case _: EvalView[G, X]        => fmoc
            case bv: BindView[G, X, Unit] => new DelegateBind[G, X, Unit](fmoc, bv.b.delegate)
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
        def endScope(scopeId: Unique.Token, result: Terminal[Unit]): Pull[G, X, Unit] =
          result match {
            case Succeeded(_)              => SucceedScope(scopeId)
            case inter @ Interrupted(_, _) => CanceledScope(scopeId, inter)
            case Fail(err)                 => FailedScope(scopeId, err)
          }

        val maybeCloseExtendedScope: F[Option[Scope[F]]] =
          // If we're opening a new top-level scope (aka, direct descendant of root),
          // close the current extended top-level scope if it is defined.
          if (scope.isRoot && extendedTopLevelScope.isDefined)
            extendedTopLevelScope.traverse_(_.close(ExitCase.Succeeded).rethrow).as(None)
          else
            F.pure(extendedTopLevelScope)

        val runner = new BuildR[G, X, End]
        val vrun = new ViewRunner(view)
        val tail = maybeCloseExtendedScope.flatMap { newExtendedScope =>
          scope.open(useInterruption).rethrow.flatMap { childScope =>
            val bb = new Bind[G, X, Unit, Unit](stream) {
              def cont(r: Terminal[Unit]): Pull[G, X, Unit] = endScope(childScope.id, r)
            }
            go(childScope, newExtendedScope, translation, runner, bb)
          }
        }
        interruptGuard(scope, view, runner)(tail).attempt
          .flatMap(_.fold(goErr(_, view), _.apply(vrun)))
      }

      def goCloseScope(close: CloseScope, view: Cont[Unit, G, X]): F[End] = {
        def addError(err: Throwable, res: Terminal[Unit]): Terminal[Unit] = res match {
          case Succeeded(_) => Fail(err)
          case Fail(err0)   => Fail(CompositeFailure(err, err0, Nil))
          // Note: close.interruption.isSome IF-AND-ONLY-IF close.exitCase is ExitCase.Cancelled
          case Interrupted(_, _) => sys.error(s"Impossible, cannot interrupt here")
        }

        def viewCont(res: Terminal[Unit]): Pull[G, X, Unit] =
          close.exitCase match {
            case ExitCase.Errored(err) => view(addError(err, res))
            case _                     => view(res)
          }

        def closeTerminal(r: Either[Throwable, Unit], ancestor: Scope[F]): Terminal[Unit] =
          close.interruption match {
            case None => r.fold(Fail(_), Succeeded(_))
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
            go(scope, extendedTopLevelScope, translation, endRunner, viewCont(unit))

          case Some(toClose) if extendLastTopLevelScope && toClose.level == 1 =>
            // Request to close the current top-level scope - if we're supposed to extend
            // it instead, leave the scope open and pass it to the continuation
            extendedTopLevelScope.traverse_(_.close(ExitCase.Succeeded).rethrow) *>
              toClose.openAncestor.flatMap { ancestor =>
                go(ancestor, Some(toClose), translation, endRunner, viewCont(unit))
              }

          case Some(toClose) =>
            toClose.close(close.exitCase).flatMap { r =>
              toClose.openAncestor.flatMap { ancestor =>
                val res = closeTerminal(r, ancestor)
                go(ancestor, extendedTopLevelScope, translation, endRunner, viewCont(res))
              }
            }

          case None =>
            // scope already closed, continue with current scope
            val result = close.interruption.getOrElse(unit)
            go(scope, extendedTopLevelScope, translation, endRunner, viewCont(result))
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

            case fmout: FlatMapOutput[g, z, _] => // y = Unit
              goFlatMapOut[z](fmout, view.asInstanceOf[View[g, X, Unit]])

            case tst: Translate[h, g, _] => // y = Unit
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

            case u0: Uncons[g, y] =>
              val u = u0.asInstanceOf[Uncons[G, y]]
              val v = view.asInstanceOf[View[G, X, Option[(Chunk[y], Pull[G, y, Unit])]]]
              // a Uncons is run on the same scope, without shifting.

              val runr = new BuildR[G, y, End]
              val inner = go(scope, extendedTopLevelScope, translation, runr, u.stream)
              inner.attempt.flatMap(_.fold(goErr(_, view), _.apply(new UnconsRunR(v))))

            case s0: StepLeg[g, y] =>
              val s = s0.asInstanceOf[StepLeg[G, y]]
              val v = view.asInstanceOf[View[G, X, Option[Stream.StepLeg[G, y]]]]
              scope.shiftScope(s.scope, s.toString).flatMap { stepScope =>
                val runr = new BuildR[G, y, End]
                val inner = go(stepScope, extendedTopLevelScope, translation, runr, s.stream)
                inner.attempt.flatMap(_.fold(goErr(_, view), _.apply(new StepLegRunR(v))))
              }

            case _: GetScope[_] =>
              val result = Succeeded(scope.asInstanceOf[y])
              go(scope, extendedTopLevelScope, translation, endRunner, view(result))

            case mout: MapOutput[g, z, _] => goMapOutput[z](mout, view)
            case eval: Eval[G, r]         => goEval[r](eval, view)
            case acquire: Acquire[G, y]   => goAcquire(acquire, view)
            case inScope: InScope[g, _]   => goInScope(inScope.stream, inScope.useInterruption, view)
            case int: InterruptWhen[g]    => goInterruptWhen(translation(int.haltOnSignal), view)
            case close: CloseScope        => goCloseScope(close, view)
          }
      }
    }

    val initFk: F ~> F = cats.arrow.FunctionK.id[F]

    class OuterRun(initB: B) extends Run[F, O, F[B]] { self =>
      private[this] var accB: B = initB

      override def done(scope: Scope[F]): F[B] = F.pure(accB)

      override def fail(e: Throwable): F[B] = F.raiseError(e)

      override def interrupted(scopeId: Unique.Token, err: Option[Throwable]): F[B] =
        err.fold(F.pure(accB))(F.raiseError)

      override def out(head: Chunk[O], scope: Scope[F], tail: Pull[F, O, Unit]): F[B] =
        try {
          accB = foldChunk(accB, head)
          go[F, O, B](scope, None, initFk, self, tail)
        } catch {
          case NonFatal(e) =>
            viewL(tail) match {
              case Succeeded(_)        => F.raiseError(e)
              case Fail(e2)            => F.raiseError(CompositeFailure(e2, e))
              case Interrupted(_, err) => F.raiseError(err.fold(e)(t => CompositeFailure(e, t)))
              case v: View[F, O, _]    => go[F, O, B](scope, None, initFk, self, v(Fail(e)))
            }
        }
    }

    go[F, O, B](initScope, None, initFk, new OuterRun(init), stream)
  }

  /** Inject interruption to the tail used in `flatMap`. Assures that close of the scope
    * is invoked if at the flatMap tail, otherwise switches evaluation to `interrupted` path.
    */
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
          case cs: CloseScope =>
            // Inner scope is getting closed b/c a parent was interrupted
            val cl: Pull[F, O, Unit] = CanceledScope(cs.scopeId, interruption)
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
      case a: AlgEffect[F, Unit] => a
      case r: Terminal[_]        => r
      case _                     => FlatMapOutput(p, f)
    }

  private[fs2] def translate[F[_], G[_], O](
      stream: Pull[F, O, Unit],
      fK: F ~> G
  ): Pull[G, O, Unit] =
    stream match {
      case t: Translate[e, f, _] =>
        translate[e, G, O](t.stream, t.fk.andThen(fK.asInstanceOf[f ~> G]))
      case o: Output[_]   => o
      case r: Terminal[_] => r
      case _              => Translate(stream, fK)
    }

  /* Applies the outputs of this pull to `f` and returns the result in a new `Pull`. */
  private[fs2] def mapOutput[F[_], O, P](
      stream: Pull[F, O, Unit],
      fun: O => P
  ): Pull[F, P, Unit] =
    stream match {
      case a: AlgEffect[F, _]    => a
      case t: Translate[g, f, _] => Translate[g, f, P](mapOutput(t.stream, fun), t.fk)
      case m: MapOutput[f, q, o] => MapOutput(m.stream, m.fun.andThen(fun))
      case r: Terminal[_]        => r
      case _                     => MapOutput(stream, AndThen(fun))
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
