package fs2

import cats._
import cats.implicits._
import cats.effect._
import cats.effect.implicits._

sealed trait Pull[+F[_], +O, +R] { self =>

  def attempt: Pull[F, O, Either[Throwable, R]] =
    map(r => Right(r): Either[Throwable, R]).handleErrorWith(t =>
      Pull.pure(Left(t): Either[Throwable, R]))

  def compile[F2[x] >: F[x], R2 >: R, S](initial: S)(f: (S, Chunk[O]) => S)(
      implicit F: Sync[F2]): F2[(S, R2)] =
    F.delay(Scope.unsafe[F2](None))
      .bracketCase(scope => compileWithScope[F2, R2, S](scope, initial)(f))((scope, ec) =>
        scope.closeAndThrow(ec))

  def compileAsResource[F2[x] >: F[x], R2 >: R, S](initial: S)(f: (S, Chunk[O]) => S)(
      implicit F: Sync[F2]): Resource[F2, (S, R2)] =
    Resource
      .makeCase(F.delay(Scope.unsafe[F2](None)))((scope, ec) => scope.closeAndThrow(ec))
      .flatMap { scope =>
        Resource.liftF(compileWithScope[F2, R2, S](scope, initial)(f))
      }

  private def compileWithScope[F2[x] >: F[x]: Sync, R2 >: R, S](scope: Scope[F2], initial: S)(
      f: (S, Chunk[O]) => S): F2[(S, R2)] =
    step[F2, O, R2](scope).flatMap {
      case Right((hd, tl)) => tl.compileWithScope[F2, R2, S](scope, f(initial, hd))(f)
      case Left(r)         => (initial, r: R2).pure[F2]
    }

  /** Lifts this pull to the specified effect type. */
  def covary[F2[x] >: F[x]]: Pull[F2, O, R] = this

  /** Lifts this pull to the specified effect type, output type, and resource type. */
  def covaryAll[F2[x] >: F[x], O2 >: O, R2 >: R]: Pull[F2, O2, R2] = this

  /** Lifts this pull to the specified output type. */
  def covaryOutput[O2 >: O]: Pull[F, O2, R] = this

  /** Lifts this pull to the specified resource type. */
  def covaryResource[R2 >: R]: Pull[F, O, R2] = this

  private[fs2] def step[F2[x] >: F[x]: Sync, O2 >: O, R2 >: R](
      scope: Scope[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]]

  // Note: private because this is unsound in presence of uncons, but safe when used from Stream#translate
  private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, O, R]

  def flatMap[F2[x] >: F[x], R2, O2 >: O](f: R => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    new Pull[F2, O2, R2] {
      private[fs2] def step[F3[x] >: F2[x]: Sync, O3 >: O2, R3 >: R2](
          scope: Scope[F3]): F3[Either[R3, (Chunk[O3], Pull[F3, O3, R3])]] =
        self.step(scope).flatMap {
          case Right((hd, tl)) =>
            (Right((hd, tl.flatMap(f))): Either[R3, (Chunk[O3], Pull[F3, O3, R3])]).pure[F3]
          case Left(r) => f(r).step(scope)
        }

      private[fs2] def translate[F3[x] >: F2[x], G[_]](g: F3 ~> G): Pull[G, O2, R2] =
        self.translate(g).flatMap(r => f(r).translate(g))

      override def toString = s"FlatMap($self, $f)"
    }

  final def >>[F2[x] >: F[x], O2 >: O, R2](that: => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    flatMap(_ => that)

  /** If `this` terminates with `Pull.raiseError(e)`, invoke `h(e)`. */
  def handleErrorWith[F2[x] >: F[x], O2 >: O, R2 >: R](
      h: Throwable => Pull[F2, O2, R2]): Pull[F2, O2, R2] = new Pull[F2, O2, R2] {

    private[fs2] def step[F3[x] >: F2[x]: Sync, O3 >: O2, R3 >: R2](
        scope: Scope[F3]): F3[Either[R3, (Chunk[O3], Pull[F3, O3, R3])]] =
      self
        .step[F3, O3, R3](scope)
        .map {
          case Right((hd, tl)) => Right((hd, tl.handleErrorWith(h)))
          case Left(r)         => Left(r)
        }
        .handleErrorWith(t => h(t).step(scope))

    private[fs2] def translate[F3[x] >: F2[x], G[_]](f: F3 ~> G): Pull[G, O2, R2] =
      self.translate(f).handleErrorWith(t => h(t).translate(f))

    override def toString = s"HandleErrorWith($self, $h)"
  }

  def map[R2](f: R => R2): Pull[F, O, R2] = flatMap(r => Pull.pure(f(r)))

  /** Applies the outputs of this pull to `f` and returns the result in a new `Pull`. */
  def mapOutput[O2](f: O => O2): Pull[F, O2, R] = new Pull[F, O2, R] {
    private[fs2] def step[F2[x] >: F[x]: Sync, O3 >: O2, R2 >: R](
        scope: Scope[F2]): F2[Either[R2, (Chunk[O3], Pull[F2, O3, R2])]] =
      self.step(scope).map(_.map { case (hd, tl) => (hd.map(f), tl.mapOutput(f)) })
    private[fs2] def translate[F2[x] >: F[x], G[_]](g: F2 ~> G): Pull[G, O2, R] =
      self.translate(g).mapOutput(f)
  }

  /** Tracks any resources acquired during this pull and releases them when the pull completes. */
  def scope: Pull[F, O, R] = new Pull[F, O, R] {
    private[fs2] def step[F2[x] >: F[x], O2 >: O, R2 >: R](scope: Scope[F2])(
        implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      scope.open.flatMap(childScope => self.stepWith(childScope.id).step(childScope))

    private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, O, R] =
      self.translate(f).scope

    override def toString = s"Scope($self)"
  }

  private[fs2] def stepWith(scopeId: Token): Pull[F, O, R] = new Pull[F, O, R] {
    private[fs2] def step[F2[x] >: F[x], O2 >: O, R2 >: R](scope: Scope[F2])(
        implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      scope.findScope(scopeId).map(_.map(_ -> true).getOrElse(scope -> false)).flatMap {
        case (scope, closeAfterUse) =>
          F.bracketCase((self: Pull[F2, O2, R2]).step(scope)) {
            case Right((hd, tl)) =>
              (Right((hd, tl.stepWith(scopeId))): Either[R2, (Chunk[O2], Pull[F2, O2, R2])])
                .pure[F2]
            case Left(r) =>
              if (closeAfterUse)
                scope
                  .closeAndThrow(ExitCase.Completed)
                  .as(Left(r): Either[R2, (Chunk[O2], Pull[F2, O2, R2])])
              else (Left(r): Either[R2, (Chunk[O2], Pull[F2, O2, R2])]).pure[F2]
          } {
            case (_, ExitCase.Completed) => F.unit
            case (_, other)              => if (closeAfterUse) scope.closeAndThrow(other) else F.unit
          }
      }

    private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, O, R] =
      self.translate(f).stepWith(scopeId)

    override def toString = s"StepWith($self, $scopeId)"
  }

  /** Interpret this `Pull` to produce a `Stream`. The result type `R` is discarded. */
  def stream: Stream[F, O] = Stream.fromPull(scope.map(_ => ()))

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
  def streamNoScope: Stream[F, O] = Stream.fromPull(map(_ => ()))

  def uncons: Pull[F, INothing, Either[R, (Chunk[O], Pull[F, O, R])]] =
    new Pull[F, INothing, Either[R, (Chunk[O], Pull[F, O, R])]] {

      private[fs2] def step[F2[x] >: F[x],
                            O2 >: INothing,
                            R2 >: Either[R, (Chunk[O], Pull[F, O, R])]](scope: Scope[F2])(
          implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
        self.step(scope).map(r => Left(r.asInstanceOf[R2]))

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

  private def suppressTranslate: Pull[F, O, R] = new Pull[F, O, R] {
    private[fs2] def step[F2[x] >: F[x], O2 >: O, R2 >: R](scope: Scope[F2])(
        implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      self.step(scope)

    private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, O, R] =
      self.asInstanceOf[Pull[G, O, R]]

    override def toString = s"SuppressTranslate($self)"
  }
}

object Pull extends PullInstancesLowPriority {

  /**
    * Like [[eval]] but if the effectful value fails, the exception is returned in a `Left`
    * instead of failing the pull.
    */
  def attemptEval[F[_], R](fr: F[R]): Pull[F, INothing, Either[Throwable, R]] =
    eval(fr)
      .map(r => Right(r): Either[Throwable, R])
      .handleErrorWith(t => pure[F, Either[Throwable, R]](Left(t)))

  val done: Pull[Pure, INothing, Unit] = pure(())

  def pure[F[x] >: Pure[x], R](r: R): Pull[F, INothing, R] = new Result[R](r)

  private[fs2] final class Result[R](r: R) extends Pull[Pure, INothing, R] {
    private[fs2] def step[F2[x] >: Pure[x], O2 >: INothing, R2 >: R](scope: Scope[F2])(
        implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      F.pure(Either.left(r))
    private[fs2] def translate[F2[x] >: Pure[x], G[_]](f: F2 ~> G): Pull[G, INothing, R] = this
    override def toString = s"Result($r)"
  }

  def output1[F[x] >: Pure[x], O](o: O): Pull[F, O, Unit] = new Output(Chunk.singleton(o))

  def output[F[x] >: Pure[x], O](os: Chunk[O]): Pull[F, O, Unit] =
    if (os.isEmpty) done else new Output(os)

  private final class Output[O](os: Chunk[O]) extends Pull[Pure, O, Unit] {
    private[fs2] def step[F2[x] >: Pure[x], O2 >: O, R2 >: Unit](scope: Scope[F2])(
        implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      F.pure(Right((os, done)))
    private[fs2] def translate[F2[x] >: Pure[x], G[_]](f: F2 ~> G): Pull[G, O, Unit] = this
    override def toString = s"Output($os)"
  }

  def eval[F[_], R](fr: F[R]): Pull[F, INothing, R] = new Pull[F, INothing, R] {
    private[fs2] def step[F2[x] >: F[x]: Sync, O2 >: INothing, R2 >: R](
        scope: Scope[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      (fr: F2[R]).map(Left(_))
    private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, INothing, R] =
      eval(f(fr))
    override def toString = s"Eval($fr)"
  }

  /**
    * Repeatedly uses the output of the pull as input for the next step of the pull.
    * Halts when a step terminates with `None` or `Pull.raiseError`.
    */
  def loop[F[_], O, R](using: R => Pull[F, O, Option[R]]): R => Pull[F, O, Option[R]] =
    r => using(r).flatMap { _.map(loop(using)).getOrElse(Pull.pure(None)) }

  def raiseError[F[_]: RaiseThrowable](err: Throwable): Pull[F, INothing, INothing] =
    new RaiseError(err)

  private[fs2] def raiseErrorForce[F[_]](err: Throwable): Pull[F, INothing, INothing] =
    new RaiseError(err)

  private final class RaiseError[F[_]](err: Throwable) extends Pull[F, INothing, INothing] {
    private[fs2] def step[F2[x] >: F[x], O2 >: INothing, R2 >: INothing](scope: Scope[F2])(
        implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      F.raiseError(err)

    private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, INothing, INothing] =
      new RaiseError[G](err)

    override def toString = s"RaiseError($err)"
  }

  def suspend[F[_], O, R](p: => Pull[F, O, R]): Pull[F, O, R] = done >> p

  def mapConcat[F[_], O, O2](p: Pull[F, O, Unit])(f: O => Pull[F, O2, Unit]): Pull[F, O2, Unit] =
    new Pull[F, O2, Unit] {

      private[fs2] def step[F2[x] >: F[x]: Sync, O3 >: O2, R2 >: Unit](
          scope: Scope[F2]): F2[Either[R2, (Chunk[O3], Pull[F2, O3, R2])]] =
        p.step(scope).flatMap {
          case Right((hd, tl)) =>
            tl match {
              case _: Result[_] if hd.size == 1 =>
                // nb: If tl is Pure, there's no need to propagate flatMap through the tail. Hence, we
                // check if hd has only a single element, and if so, process it directly instead of folding.
                // This allows recursive infinite streams of the form `def s: Stream[Pure,O] = Stream(o).flatMap { _ => s }`
                f(hd(0)).step(scope)
              case _ =>
                def go(idx: Int): Pull[F2, O2, Unit] =
                  if (idx == hd.size) mapConcat(tl)(f)
                  else f(hd(idx)) >> go(idx + 1) // TODO: handle interruption specifics here
                go(0).step(scope)
            }
          case Left(()) => Either.left[R2, (Chunk[O3], Pull[F2, O3, R2])](()).pure[F2]
        }

      private[fs2] def translate[F2[x] >: F[x], G[_]](g: F2 ~> G): Pull[G, O2, Unit] =
        mapConcat[G, O, O2](p.translate(g))(o => f(o).translate(g))

      override def toString = s"MapConcat($p, $f)"
    }

  private[fs2] def acquire[F[_], R](
      resource: F[R],
      release: (R, ExitCase[Throwable]) => F[Unit]): Pull[F, INothing, R] =
    new Pull[F, INothing, R] {

      private[fs2] def step[F2[x] >: F[x], O2 >: INothing, R2 >: R](scope: Scope[F2])(
          implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
        scope.acquire(resource, release).flatMap {
          case Right(rt) => F.pure(Left(rt))
          case Left(t)   => F.raiseError(t)
        }

      private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, INothing, R] =
        acquire[G, R](f(resource), (r, ec) => f(release(r, ec)))

      override def toString = s"Acquire($resource, $release)"
    }

  def getScope[F[_]]: Pull[F, INothing, Scope[F]] = new Pull[F, INothing, Scope[F]] {

    private[fs2] def step[F2[x] >: F[x]: Sync, O2 >: INothing, R2 >: Scope[F]](
        scope: Scope[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      Either
        .left[R2, (Chunk[O2], Pull[F2, O2, R2])](scope.asInstanceOf[Scope[F]])
        .pure[F2]

    private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, INothing, Scope[F]] =
      getScope[G].asInstanceOf[Pull[G, INothing, Scope[F]]]

    override def toString = "GetScope"
  }

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
