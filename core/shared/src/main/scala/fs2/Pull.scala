package fs2

import cats._
import cats.implicits._
import cats.effect._

/**
  * A `p: Pull[F,O,R]` reads values from one or more streams, outputs values of type `O`,
  * and returns a result of type `R`.
  *
  * Any resources acquired by `p` are registered in the active scope and released when that
  * scope is closed. Converting a pull to a stream via `p.stream` introduces a scope.
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
sealed trait Pull[+F[_], +O, +R] extends Serializable { self =>

  /**
    * Used during compilation of a pull to make progress. A single step of a pull
    * results in either:
    *  - reaching the end of the pull, represented by a 'Left(r)'
    *  - emission of a chunk of 0 or more output values along with a new pull representing the rest of the computation
    */
  protected def step[F2[x] >: F[x]: Sync, O2 >: O, R2 >: R](
      scope: Scope[F2]): F2[StepResult[F2, O2, R2]]

  /** Checks if the scope has been interrupted before running the `ifNotInterrupted` task. */
  protected def checkForInterrupt[F2[x] >: F[x]: Sync, O2 >: O, R2 >: R](scope: Scope[F2])(
      ifNotInterrupted: F2[StepResult[F2, O2, R2]]): F2[StepResult[F2, O2, R2]] =
    scope.isInterrupted.flatMap {
      case None    => ifNotInterrupted
      case Some(e) => StepResult.interrupted(e).pure[F2]
    }

  /** Alias for `map(_ => r2)`. */
  final def as[R2](r2: R2): Pull[F, O, R2] = map(_ => r2)

  /** Returns a pull with the result wrapped in `Right`, or an error wrapped in `Left` if the pull has failed. */
  final def attempt: Pull[F, O, Either[Throwable, R]] =
    map(r => Right(r): Either[Throwable, R]).handleErrorWith(t =>
      Pull.pure(Left(t): Either[Throwable, R]))

  /** Compiles a pull to an effectful value using a chunk based fold. */
  private[fs2] final def compile[F2[x] >: F[x], R2 >: R, S](initial: S)(f: (S, Chunk[O]) => S)(
      implicit F: Sync[F2]): F2[(S, Option[R2])] =
    compileAsResource[F2, R2, S](initial)(f).use(F.pure)

  /**
    * Compiles a pull to an effectful resource using a chunk based fold.
    *
    * A root scope is allocated as a resource and used during pull compilation. The lifetime of
    * the root scope is tied to the returned resource, allowing root scope lifetime extension
    * via methods on `Resource` such as `use`.
    */
  private[fs2] final def compileAsResource[F2[x] >: F[x], R2 >: R, S](initial: S)(
      f: (S, Chunk[O]) => S)(implicit F: Sync[F2]): Resource[F2, (S, Option[R2])] =
    Resource
      .makeCase(F.delay(Scope.unsafe[F2](None, None)))((scope, ec) => scope.closeAndThrow(ec))
      .flatMap { scope =>
        def resourceEval[A](fa: F2[A]): Resource[F2, A] =
          Resource.suspend(fa.map(a => a.pure[Resource[F2, ?]]))
        resourceEval(compileWithScope[F2, R2, S](scope, initial)(f))
      }

  /**
    * Compiles this pull to an effectful value using a chunk based fols and the supplied scope
    * for resource tracking.
    */
  private def compileWithScope[F2[x] >: F[x]: Sync, R2 >: R, S](scope: Scope[F2], initial: S)(
      f: (S, Chunk[O]) => S): F2[(S, Option[R2])] =
    step[F2, O, R2](scope).flatMap {
      case StepResult.Output(_, hd, tl) =>
        tl.compileWithScope[F2, R2, S](scope, f(initial, hd))(f)
      case StepResult.Done(r) => (initial, Some(r: R2): Option[R2]).pure[F2]
      case StepResult.Interrupted(err) =>
        err match {
          case None    => Sync[F2].pure((initial, None))
          case Some(e) => Sync[F2].raiseError(e)
        }
    }

  /** Lifts this pull to the specified effect type. */
  final def covary[F2[x] >: F[x]]: Pull[F2, O, R] = this

  /** Lifts this pull to the specified effect type, output type, and result type. */
  final def covaryAll[F2[x] >: F[x], O2 >: O, R2 >: R]: Pull[F2, O2, R2] = this

  /** Lifts this pull to the specified output type. */
  final def covaryOutput[O2 >: O]: Pull[F, O2, R] = this

  /** Lifts this pull to the specified result type. */
  final def covaryResult[R2 >: R]: Pull[F, O, R2] = this

  // Note: private because this is unsound in presence of uncons, but safe when used from Stream#translate
  private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, O, R]

  /** Applies the result of this pull to `f` and returns the result. */
  def flatMap[F2[x] >: F[x], R2, O2 >: O](f: R => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    flatMap_(f)(0)

  private def flatMap_[F2[x] >: F[x], R2, O2 >: O](f: R => Pull[F2, O2, R2])(
      depth: Int): Pull[F2, O2, R2] =
    new Pull[F2, O2, R2] {
      protected def step[F3[x] >: F2[x]: Sync, O3 >: O2, R3 >: R2](
          scope: Scope[F3]): F3[StepResult[F3, O3, R3]] =
        checkForInterrupt[F3, O3, R3](scope) {
          self.step(scope).flatMap {
            case StepResult.Output(scope, hd, tl) =>
              StepResult.output[F3, O3, R3](scope, hd, tl.flatMap(f)).pure[F3]
            case StepResult.Done(r) => f(r).step(scope)
            case StepResult.Interrupted(err) =>
              StepResult.interrupted[F3, O3, R3](err).pure[F3]
          }
        }

      private[fs2] def translate[F3[x] >: F2[x], G[_]](g: F3 ~> G): Pull[G, O2, R2] =
        self.translate(g).flatMap(r => f(r).translate(g))

      override def flatMap[F3[x] >: F2[x], R3, O3 >: O2](
          g: R2 => Pull[F3, O3, R3]): Pull[F3, O3, R3] =
        if (depth < Pull.MaxDepth) self.flatMap_(r => f(r).flatMap_(g)(depth))(depth + 1)
        else super.flatMap(g)

      override def toString = s"FlatMap($self, $f)"
    }

  /** Alias for `flatMap(_ => that)`. */
  final def >>[F2[x] >: F[x], O2 >: O, R2](that: => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    flatMap(_ => that)

  /**
    * Maps the supplied function over the *outputs* of this pull and concatenates all the results.
    * The result type of this pull, and of each mapped pull, must be unit.
    */
  def flatMapOutput[F2[x] >: F[x], O2](f: O => Pull[F2, O2, Unit])(
      implicit ev: R <:< Unit): Pull[F2, O2, Unit] =
    flatMapOutput_(f)(0)

  def flatMapOutput_[F2[x] >: F[x], O2](f: O => Pull[F2, O2, Unit])(depth: Int)(
      implicit ev: R <:< Unit): Pull[F2, O2, Unit] =
    new Pull[F2, O2, Unit] {

      protected def step[F3[x] >: F2[x]: Sync, O3 >: O2, R2 >: Unit](
          scope: Scope[F3]): F3[StepResult[F3, O3, R2]] =
        checkForInterrupt[F3, O3, R2](scope) {
          self.step(scope).flatMap {
            case StepResult.Output(scope, hd, tl) =>
              tl match {
                case _: Pull.Result[_] if hd.size == 1 =>
                  // nb: If tl is Pure, there's no need to propagate flatMap through the tail. Hence, we
                  // check if hd has only a single element, and if so, process it directly instead of folding.
                  // This allows recursive infinite streams of the form `def s: Stream[Pure,O] = Stream(o).flatMap { _ => s }`
                  f(hd(0)).step(scope)
                case _ =>
                  def go(idx: Int): Pull[F3, O2, Unit] =
                    if (idx == hd.size) tl.flatMapOutput(f)
                    else f(hd(idx)) >> go(idx + 1)
                  go(0).step(scope)
              }
            case StepResult.Done(_) => StepResult.done[F3, O3, R2](()).pure[F3]
            case StepResult.Interrupted(err) =>
              StepResult.interrupted[F3, O3, R2](err).pure[F3]
          }
        }

      private[fs2] def translate[F3[x] >: F2[x], G[_]](g: F3 ~> G): Pull[G, O2, Unit] =
        self.translate(g).flatMapOutput[G, O2](o => f(o).translate(g))

      override def flatMapOutput[F3[x] >: F2[x], O3](g: O2 => Pull[F3, O3, Unit])(
          implicit ev2: Unit <:< Unit): Pull[F3, O3, Unit] =
        if (depth < Pull.MaxDepth)
          self.flatMapOutput_(o => f(o).flatMapOutput_(g)(depth))(depth + 1)
        else super.flatMapOutput(g)

      override def toString = s"FlatMapOutput($self, $f)"
    }

  /** If `this` terminates with `Pull.raiseError(e)`, invoke `h(e)`. */
  def handleErrorWith[F2[x] >: F[x], O2 >: O, R2 >: R](
      h: Throwable => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    handleErrorWith_(h)(0)

  private def handleErrorWith_[F2[x] >: F[x], O2 >: O, R2 >: R](h: Throwable => Pull[F2, O2, R2])(
      depth: Int): Pull[F2, O2, R2] = new Pull[F2, O2, R2] {

    protected def step[F3[x] >: F2[x]: Sync, O3 >: O2, R3 >: R2](
        scope: Scope[F3]): F3[StepResult[F3, O3, R3]] =
      self
        .step[F3, O3, R3](scope)
        .map {
          case StepResult.Output(scope, hd, tl) =>
            StepResult.output[F3, O3, R3](scope, hd, tl.handleErrorWith(h))
          case StepResult.Done(r) => StepResult.done[F3, O3, R3](r)
          case StepResult.Interrupted(err) =>
            StepResult.interrupted[F3, O3, R3](err)
        }
        .handleErrorWith(t => h(t).step[F3, O3, R3](scope))

    private[fs2] def translate[F3[x] >: F2[x], G[_]](f: F3 ~> G): Pull[G, O2, R2] =
      self.translate(f).handleErrorWith(t => h(t).translate(f))

    override def handleErrorWith[F3[x] >: F2[x], O3 >: O2, R3 >: R2](
        i: Throwable => Pull[F3, O3, R3]): Pull[F3, O3, R3] =
      if (depth < Pull.MaxDepth)
        self.handleErrorWith_(t => h(t).handleErrorWith_(i)(depth))(depth + 1)
      else super.handleErrorWith(i)

    override def toString = s"HandleErrorWith($self, $h)"
  }

  /** Applies the result of this pull to `f` and returns the result in a new `Pull`. */
  def map[R2](f: R => R2): Pull[F, O, R2] = flatMap(r => Pull.pure(f(r)))

  /** Applies the outputs of this pull to `f` and returns the result in a new `Pull`. */
  def mapOutput[O2](f: O => O2): Pull[F, O2, R] = new Pull[F, O2, R] {
    protected def step[F2[x] >: F[x]: Sync, O3 >: O2, R2 >: R](
        scope: Scope[F2]): F2[StepResult[F2, O3, R2]] =
      self.step(scope).map {
        case StepResult.Output(scope, hd, tl) =>
          StepResult.output(scope, hd.map(f), tl.mapOutput(f))
        case StepResult.Done(r)          => StepResult.done(r)
        case StepResult.Interrupted(err) => StepResult.interrupted(err)
      }
    private[fs2] def translate[F2[x] >: F[x], G[_]](g: F2 ~> G): Pull[G, O2, R] =
      self.translate(g).mapOutput(f)
  }

  /** Run `p2` after `this`, regardless of errors during `this`, then reraise any errors encountered during `this`. */
  def onComplete[F2[x] >: F[x], O2 >: O, R2 >: R](p2: => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    handleErrorWith(e => p2 >> Pull.raiseErrorForce(e)) >> p2

  /** Tracks any resources acquired during this pull and releases them when the pull completes. */
  def scope: Pull[F, O, R] = new Pull[F, O, R] {
    protected def step[F2[x] >: F[x], O2 >: O, R2 >: R](scope: Scope[F2])(
        implicit F: Sync[F2]): F2[StepResult[F2, O2, R2]] =
      scope
        .open(None)
        .flatMap(childScope => self.stepWith(childScope.id, None).step(childScope))

    private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, O, R] =
      self.translate(f).scope

    override def toString = s"Scope($self)"
  }

  private[fs2] def interruptScope[F2[x] >: F[x]](implicit F: Concurrent[F2]): Pull[F2, O, Unit] =
    new Pull[F2, O, Unit] {
      protected def step[F3[x] >: F2[x], O2 >: O, R2 >: Unit](scope: Scope[F3])(
          implicit F: Sync[F3]): F3[StepResult[F3, O2, R2]] =
        scope
          .open(Some(F).asInstanceOf[Option[Concurrent[F3]]])
          .flatMap(childScope => self.void.stepWith(childScope.id, Some(())).step(childScope))

      private[fs2] def translate[F3[x] >: F2[x], G[_]](f: F3 ~> G): Pull[G, O, Unit] =
        // We don't have a Concurrent[G] instance here so we convert the interruptScope to a regular scope
        // This is what happened in 1.0 as well, though it was hidden a bit by the TranslateInterrupt type class
        self.void.translate(f).scope

      override def toString = s"InterruptScope($F, $self)"
    }

  private[fs2] def stepWith[R2 >: R](scopeId: Token, onInterrupt: Option[R2]): Pull[F, O, R2] =
    new Pull[F, O, R2] {
      protected def step[F2[x] >: F[x], O2 >: O, R3 >: R2](scope: Scope[F2])(
          implicit F: Sync[F2]): F2[StepResult[F2, O2, R3]] =
        scope.findScope(scopeId).map(_.map(_ -> true).getOrElse(scope -> false)).flatMap {
          case (scope, closeAfterUse) =>
            F.bracketCase((self: Pull[F2, O2, R3]).step(scope)) {
              case StepResult.Output(scope, hd, tl) =>
                StepResult.output[F2, O2, R3](scope, hd, tl.stepWith(scopeId, onInterrupt)).pure[F2]
              case StepResult.Done(r) =>
                if (closeAfterUse)
                  scope
                    .closeAndThrow(ExitCase.Completed)
                    .as(StepResult.done[F2, O2, R3](r))
                else StepResult.done[F2, O2, R3](r).pure[F2]
              case StepResult.Interrupted(err) =>
                val result: F2[StepResult[F2, O2, R3]] = onInterrupt match {
                  case None => StepResult.interrupted[F2, O2, R3](err).pure[F2]
                  case Some(r) =>
                    err match {
                      case None    => StepResult.done[F2, O2, R3](r).pure[F2]
                      case Some(e) => F.raiseError(e)
                    }
                }
                if (closeAfterUse) scope.closeAndThrow(ExitCase.Canceled) >> result
                else result
            } {
              case (_, ExitCase.Completed) => F.unit
              case (_, other)              => if (closeAfterUse) scope.closeAndThrow(other) else F.unit
            }
        }

      private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, O, R2] =
        self.translate(f).stepWith(scopeId, onInterrupt)

      override def toString = s"StepWith($self, $scopeId)"
    }

  /** Interpret this `Pull` to produce a `Stream`. The result type `R` is discarded. */
  def stream(implicit ev: R <:< Unit): Stream[F, O] = {
    val _ = ev
    Stream.fromPull(this.asInstanceOf[Pull[F, O, Unit]].scope)
  }

  /**
    * Like [[stream]] but no scope is inserted around the pull, resulting in any resources being
    * promoted to the current scope of the stream, extending the resource lifetime. Typically used
    * as a performance optimization, where resource lifetime can be extended in exchange for faster
    * execution.
    *
    * Caution: this can result in resources with greatly extended lifecycles if the pull
    * discards parts of the stream from which it was created. This could lead to memory leaks
    * so be very careful when using this function. For example, a pull that emits the first element
    * and discards the tail could discard the release of one or more resources that were acquired
    * in order to produce the first element. Normally, these resources would be registered in the
    * scope generated by the pull-to-stream conversion and hence released as part of that scope
    * closing but when using `streamNoScope`, they get promoted to the current stream scope,
    * which may be infinite in the worst case.
    */
  def streamNoScope(implicit ev: R <:< Unit): Stream[F, O] = {
    val _ = ev
    Stream.fromPull(this.asInstanceOf[Pull[F, O, Unit]])
  }

  /**
    * Steps this pull and returns the result as the result of a new pull.
    * Note: this operation is private as `translate` is unsound.
    */
  private[fs2] def uncons: Pull[F, INothing, Either[R, (Chunk[O], Pull[F, O, R])]] =
    new Pull[F, INothing, Either[R, (Chunk[O], Pull[F, O, R])]] {

      protected def step[F2[x] >: F[x], O2 >: INothing, R2 >: Either[R, (Chunk[O], Pull[F, O, R])]](
          scope: Scope[F2])(implicit F: Sync[F2]): F2[StepResult[F2, O2, R2]] =
        self.step(scope).map {
          case StepResult.Output(scope, hd, tl) =>
            StepResult.done[F2, O2, R2](Right((hd, tl)).asInstanceOf[R2])
          case StepResult.Done(r) => StepResult.done[F2, O2, R2](Left(r))
          case StepResult.Interrupted(err) =>
            StepResult.interrupted[F2, O2, R2](err)
        }

      private[fs2] def translate[F2[x] >: F[x], G[_]](
          f: F2 ~> G): Pull[G, INothing, Either[R, (Chunk[O], Pull[F, O, R])]] = {
        val p: Pull[G, O, R] = self.translate(f)
        val q: Pull[G, INothing, Either[R, (Chunk[O], Pull[G, O, R])]] = p.uncons.map {
          case Right((hd, tl)) => Right((hd, tl.suppressTranslate))
          case Left(r)         => Left(r)
        }
        q.asInstanceOf[Pull[G, INothing, Either[R, (Chunk[O], Pull[F, O, R])]]]
      }

      override def toString = s"Uncons($self)"
    }

  /** Suppresses future calls to `translate`. Used in the implementation of [[uncons]]. */
  private def suppressTranslate: Pull[F, O, R] = new Pull[F, O, R] {
    protected def step[F2[x] >: F[x], O2 >: O, R2 >: R](scope: Scope[F2])(
        implicit F: Sync[F2]): F2[StepResult[F2, O2, R2]] =
      self.step(scope)

    private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, O, R] =
      self.asInstanceOf[Pull[G, O, R]]

    override def toString = s"SuppressTranslate($self)"
  }

  /** Replaces the result of this pull with a unit. */
  def void: Pull[F, O, Unit] = map(_ => ())
}

object Pull extends PullInstancesLowPriority {

  private final val MaxDepth = 24

  /**
    * Acquire a resource within a `Pull`. The cleanup action will be run at the end
    * of the scope which executes the returned `Pull`. The acquired
    * resource is returned as the result value of the pull.
    */
  def acquire[F[_], R](resource: F[R])(release: R => F[Unit]): Pull[F, INothing, R] =
    acquireCase(resource)((r, ec) => release(r))

  /** Like [[acquire]] but the release function is given an `ExitCase[Throwable]`. */
  def acquireCase[F[_], R](resource: F[R])(
      release: (R, ExitCase[Throwable]) => F[Unit]): Pull[F, INothing, R] =
    new Pull[F, INothing, R] {

      protected def step[F2[x] >: F[x], O2 >: INothing, R2 >: R](scope: Scope[F2])(
          implicit F: Sync[F2]): F2[StepResult[F2, O2, R2]] =
        scope.acquire(resource, release).flatMap {
          case Right(rt) => F.pure(StepResult.done(rt))
          case Left(t)   => F.raiseError(t)
        }

      private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, INothing, R] =
        acquireCase[G, R](f(resource))((r, ec) => f(release(r, ec)))

      override def toString = s"Acquire($resource, $release)"
    }

  /**
    * Like [[eval]] but if the effectful value fails, the exception is returned in a `Left`
    * instead of failing the pull.
    */
  def attemptEval[F[_], R](fr: F[R]): Pull[F, INothing, Either[Throwable, R]] =
    eval(fr)
      .map(r => Right(r): Either[Throwable, R])
      .handleErrorWith(t => pure[F, Either[Throwable, R]](Left(t)))

  /** The completed `Pull`. Reads and outputs nothing. */
  val done: Pull[Pure, INothing, Unit] = pure(())

  /** Evaluates the supplied effectful value and returns the result. */
  def eval[F[_], R](fr: F[R]): Pull[F, INothing, R] = new Pull[F, INothing, R] {
    protected def step[F2[x] >: F[x]: Sync, O2 >: INothing, R2 >: R](
        scope: Scope[F2]): F2[StepResult[F2, O2, R2]] =
      checkForInterrupt[F2, O2, R2](scope) {
        scope.interruptibleEval(fr).flatMap {
          case Right(res) =>
            res.fold(Sync[F2].raiseError(_), r => StepResult.done[F2, O2, R2](r).pure[F2])
          case Left(err) => StepResult.interrupted(err).pure[F2]
        }
      }
    private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, INothing, R] =
      eval(f(fr))
    override def toString = s"Eval($fr)"
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
  def fromEither[F[_]]: PartiallyAppliedFromEither[F] = new PartiallyAppliedFromEither[F]

  final class PartiallyAppliedFromEither[F[_]] {
    def apply[A](either: Either[Throwable, A])(implicit ev: RaiseThrowable[F]): Pull[F, A, Unit] =
      either.fold(Pull.raiseError[F], Pull.output1)
  }

  /** Creates a pull that returns the current scope as its result. */
  private[fs2] def getScope[F[_]]: Pull[F, INothing, Scope[F]] = new Pull[F, INothing, Scope[F]] {

    protected def step[F2[x] >: F[x]: Sync, O2 >: INothing, R2 >: Scope[F]](
        scope: Scope[F2]): F2[StepResult[F2, O2, R2]] =
      StepResult.done[F2, O2, R2](scope.asInstanceOf[Scope[F]]).pure[F2]

    private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, INothing, Scope[F]] =
      getScope[G].asInstanceOf[Pull[G, INothing, Scope[F]]]

    override def toString = "GetScope"
  }

  /**
    * Repeatedly uses the output of the pull as input for the next step of the pull.
    * Halts when a step terminates with `None` or `Pull.raiseError`.
    */
  def loop[F[_], O, R](using: R => Pull[F, O, Option[R]]): R => Pull[F, O, Option[R]] =
    r => using(r).flatMap { _.map(loop(using)).getOrElse(Pull.pure(None)) }

  /** Creates a pull that outputs a single value and returns a unit. */
  def output1[F[x] >: Pure[x], O](o: O): Pull[F, O, Unit] = new Output(Chunk.singleton(o))

  /** Creates a pull that outputs a single chunk and returns a unit. */
  def output[F[x] >: Pure[x], O](os: Chunk[O]): Pull[F, O, Unit] =
    if (os.isEmpty) done else new Output(os)

  private final class Output[O](os: Chunk[O]) extends Pull[Pure, O, Unit] {
    protected def step[F2[x] >: Pure[x], O2 >: O, R2 >: Unit](scope: Scope[F2])(
        implicit F: Sync[F2]): F2[StepResult[F2, O2, R2]] =
      F.pure(StepResult.Output(scope, os, done))
    private[fs2] def translate[F2[x] >: Pure[x], G[_]](f: F2 ~> G): Pull[G, O, Unit] = this
    override def toString = s"Output($os)"
  }

  /** Pull that outputs nothing and has result of `r`. */
  def pure[F[x] >: Pure[x], R](r: R): Pull[F, INothing, R] = new Result[R](r)

  private[fs2] final class Result[R](r: R) extends Pull[Pure, INothing, R] {
    protected def step[F2[x] >: Pure[x], O2 >: INothing, R2 >: R](scope: Scope[F2])(
        implicit F: Sync[F2]): F2[StepResult[F2, O2, R2]] =
      F.pure(StepResult.done(r))
    private[fs2] def translate[F2[x] >: Pure[x], G[_]](f: F2 ~> G): Pull[G, INothing, R] = this
    override def toString = s"Result($r)"
  }

  /**
    * Creates a pull that outputs nothing and fails with the given error.
    *
    * The `F` type must be explicitly provided (e.g., via `raiseError[IO]` or `raiseError[Fallible]`).
    */
  def raiseError[F[_]: RaiseThrowable](err: Throwable): Pull[F, INothing, INothing] =
    new RaiseError(err)

  /**
    * Alternative to `raiseError` which does not require a `RaiseThrowable` constraint on `F`.
    * Used internal to propagate caught errors.
    */
  private[fs2] def raiseErrorForce[F[_]](err: Throwable): Pull[F, INothing, INothing] =
    new RaiseError(err)

  private final class RaiseError[F[_]](err: Throwable) extends Pull[F, INothing, INothing] {
    protected def step[F2[x] >: F[x], O2 >: INothing, R2 >: INothing](scope: Scope[F2])(
        implicit F: Sync[F2]): F2[StepResult[F2, O2, R2]] =
      F.raiseError(err)

    private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, INothing, INothing] =
      new RaiseError[G](err)

    override def toString = s"RaiseError($err)"
  }

  /**
    * Returns a pull that evaluates the supplied by-name each time the pull is used,
    * allowing use of a mutable value in pull computations.
    */
  def suspend[F[_], O, R](p: => Pull[F, O, R]): Pull[F, O, R] = done >> p

  /** `Sync` instance for `Pull`. */
  implicit def syncInstance[F[_], O](
      implicit ev: ApplicativeError[F, Throwable]): Sync[Pull[F, O, ?]] =
    new Sync[Pull[F, O, ?]] {
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
      def bracketCase[A, B](acquire: Pull[F, O, A])(use: A => Pull[F, O, B])(
          release: (A, ExitCase[Throwable]) => Pull[F, O, Unit]): Pull[F, O, B] =
        ???
      /* TODO
        Pull.fromFreeC(
          FreeC
            .syncInstance[Algebra[F, O, ?]]
            .bracketCase(acquire.get)(a => use(a).get)((a, c) => release(a, c).get))
     */
    }
}

private[fs2] trait PullInstancesLowPriority {

  implicit def monadInstance[F[_], O]: Monad[Pull[F, O, ?]] =
    new Monad[Pull[F, O, ?]] {
      def pure[A](a: A): Pull[F, O, A] = Pull.pure(a)
      def flatMap[A, B](p: Pull[F, O, A])(f: A => Pull[F, O, B]) = p.flatMap(f)
      def tailRecM[A, B](a: A)(f: A => Pull[F, O, Either[A, B]]) =
        f(a).flatMap {
          case Left(a)  => tailRecM(a)(f)
          case Right(b) => Pull.pure(b)
        }
    }
}
