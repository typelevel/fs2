package fs2.tagless

import fs2.{Chunk, Fallible, INothing, Pure, RaiseThrowable, Scope}
import fs2.internal.{CompileScope, Token}

import cats._
import cats.implicits._
import cats.effect._
import cats.effect.implicits._

import scala.collection.compat._

// trait CompilationScope {
//   def open: F[Scope]
//   def close(ec: ExitCase): F[Unit]
//   def findStepScope(scopeId: Token): F[Option[Scope]]
//   def acquireResource[R](fr: F[R], release: (R, ExitCase[Throwable]) => F[Unit]): F[Either[Throwable, (R, Token)]]
//   def releaseResource(id: Token, ec: ExitCase[Throwable]): F[Either[Throwable, Unit]]
// }

sealed trait Pull[+F[_], +O, +R] { self =>

  def attempt: Pull[F, O, Either[Throwable, R]] =
    map(r => Right(r): Either[Throwable, R]).handleErrorWith(t =>
      Pull.pure(Left(t): Either[Throwable, R]))

  def compile[F2[x] >: F[x], R2 >: R, S](initial: S)(f: (S, Chunk[O]) => S)(
      implicit F: Sync[F2]): F2[(S, R2)] =
    CompileScope
      .newRoot[F2]
      .bracketCase(scope => compileScope[F2, R2, S](scope, initial)(f))((scope, ec) =>
        scope.close(ec).rethrow)

  private def compileScope[F2[x] >: F[x]: Sync, R2 >: R, S](scope: CompileScope[F2], initial: S)(
      f: (S, Chunk[O]) => S): F2[(S, R2)] =
    step[F2, O, R2](scope).flatMap {
      case Right((hd, tl)) => tl.compileScope[F2, R2, S](scope, f(initial, hd))(f)
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
      scope: CompileScope[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]]

  // Note: private because this is unsound in presence of uncons, but safe when used from Stream#translate
  private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, O, R]

  def flatMap[F2[x] >: F[x], R2, O2 >: O](f: R => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    new Pull[F2, O2, R2] {
      private[fs2] def step[F3[x] >: F2[x]: Sync, O3 >: O2, R3 >: R2](
          scope: CompileScope[F3]): F3[Either[R3, (Chunk[O3], Pull[F3, O3, R3])]] =
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
        scope: CompileScope[F3]): F3[Either[R3, (Chunk[O3], Pull[F3, O3, R3])]] =
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
        scope: CompileScope[F2]): F2[Either[R2, (Chunk[O3], Pull[F2, O3, R2])]] =
      self.step(scope).map(_.map { case (hd, tl) => (hd.map(f), tl.mapOutput(f)) })
    private[fs2] def translate[F2[x] >: F[x], G[_]](g: F2 ~> G): Pull[G, O2, R] =
      self.translate(g).mapOutput(f)
  }

  /** Tracks any resources acquired during this pull and releases them when the pull completes. */
  def scope: Pull[F, O, R] = new Pull[F, O, R] {
    private[fs2] def step[F2[x] >: F[x], O2 >: O, R2 >: R](scope: CompileScope[F2])(
        implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      scope.open(None).rethrow.flatMap(childScope => self.stepWith(childScope.id).step(childScope))

    private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, O, R] =
      self.translate(f).scope

    override def toString = s"Scope($self)"
  }

  private[fs2] def stepWith(scopeId: Token): Pull[F, O, R] = new Pull[F, O, R] {
    private[fs2] def step[F2[x] >: F[x], O2 >: O, R2 >: R](scope: CompileScope[F2])(
        implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      scope.findStepScope(scopeId).map(_.map(_ -> true).getOrElse(scope -> false)).flatMap {
        case (scope, closeAfterUse) =>
          F.bracketCase((self: Pull[F2, O2, R2]).step(scope)) {
            case Right((hd, tl)) =>
              (Right((hd, tl.stepWith(scopeId))): Either[R2, (Chunk[O2], Pull[F2, O2, R2])])
                .pure[F2]
            case Left(r) =>
              if (closeAfterUse)
                scope
                  .close(ExitCase.Completed)
                  .rethrow
                  .as(Left(r): Either[R2, (Chunk[O2], Pull[F2, O2, R2])])
              else (Left(r): Either[R2, (Chunk[O2], Pull[F2, O2, R2])]).pure[F2]
          } {
            case (_, ExitCase.Completed) => F.unit
            case (_, other)              => if (closeAfterUse) scope.close(other).rethrow else F.unit
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
                            R2 >: Either[R, (Chunk[O], Pull[F, O, R])]](scope: CompileScope[F2])(
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
    private[fs2] def step[F2[x] >: F[x], O2 >: O, R2 >: R](scope: CompileScope[F2])(
        implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      self.step(scope)

    private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, O, R] =
      self.asInstanceOf[Pull[G, O, R]]

    override def toString = s"SuppressTranslate($self)"
  }
}

object Pull extends PullInstancesLowPriority {

  val done: Pull[Pure, INothing, Unit] = pure(())

  def pure[F[x] >: Pure[x], R](r: R): Pull[F, INothing, R] = new Result[R](r)

  private[fs2] final class Result[R](r: R) extends Pull[Pure, INothing, R] {
    private[fs2] def step[F2[x] >: Pure[x], O2 >: INothing, R2 >: R](scope: CompileScope[F2])(
        implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      F.pure(Either.left(r))
    private[fs2] def translate[F2[x] >: Pure[x], G[_]](f: F2 ~> G): Pull[G, INothing, R] = this
    override def toString = s"Result($r)"
  }

  def output1[F[x] >: Pure[x], O](o: O): Pull[F, O, Unit] = new Output(Chunk.singleton(o))

  def output[F[x] >: Pure[x], O](os: Chunk[O]): Pull[F, O, Unit] =
    if (os.isEmpty) done else new Output(os)

  private final class Output[O](os: Chunk[O]) extends Pull[Pure, O, Unit] {
    private[fs2] def step[F2[x] >: Pure[x], O2 >: O, R2 >: Unit](scope: CompileScope[F2])(
        implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      F.pure(Right((os, done)))
    private[fs2] def translate[F2[x] >: Pure[x], G[_]](f: F2 ~> G): Pull[G, O, Unit] = this
    override def toString = s"Output($os)"
  }

  def eval[F[_], R](fr: F[R]): Pull[F, INothing, R] = new Pull[F, INothing, R] {
    private[fs2] def step[F2[x] >: F[x]: Sync, O2 >: INothing, R2 >: R](
        scope: CompileScope[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
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
    private[fs2] def step[F2[x] >: F[x], O2 >: INothing, R2 >: INothing](scope: CompileScope[F2])(
        implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      F.raiseError(err)

    private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, INothing, INothing] =
      new RaiseError[G](err)

    override def toString = s"RaiseError($err)"
  }

  def mapConcat[F[_], O, O2](p: Pull[F, O, Unit])(f: O => Pull[F, O2, Unit]): Pull[F, O2, Unit] =
    new Pull[F, O2, Unit] {

      private[fs2] def step[F2[x] >: F[x]: Sync, O3 >: O2, R2 >: Unit](
          scope: CompileScope[F2]): F2[Either[R2, (Chunk[O3], Pull[F2, O3, R2])]] =
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

  private[fs2] def acquireWithToken[F[_], R](
      resource: F[R],
      release: (R, ExitCase[Throwable]) => F[Unit]): Pull[F, INothing, (R, Token)] =
    new Pull[F, INothing, (R, Token)] {

      private[fs2] def step[F2[x] >: F[x], O2 >: INothing, R2 >: (R, Token)](
          scope: CompileScope[F2])(
          implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
        scope.acquireResource(resource, release).flatMap {
          case Right(rt) => F.pure(Left(rt))
          case Left(t)   => F.raiseError(t)
        }

      private[fs2] def translate[F2[x] >: F[x], G[_]](f: F2 ~> G): Pull[G, INothing, (R, Token)] =
        acquireWithToken[G, R](f(resource), (r, ec) => f(release(r, ec)))

      override def toString = s"Acquire($resource, $release)"
    }

  private[fs2] def release(token: Token): Pull[Pure, INothing, Unit] =
    new Pull[Pure, INothing, Unit] {

      private[fs2] def step[F2[x] >: Pure[x], O2 >: INothing, R2 >: Unit](scope: CompileScope[F2])(
          implicit F: Sync[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
        scope.releaseResource(token, ExitCase.Completed).flatMap {
          case Right(_) => F.pure(Left(()))
          case Left(t)  => F.raiseError(t)
        }

      private[fs2] def translate[F2[x] >: Pure[x], G[_]](f: F2 ~> G): Pull[G, INothing, Unit] = this

      override def toString = s"Release($token)"
    }

  def getScope[F[_]]: Pull[F, INothing, Scope[F]] = new Pull[F, INothing, Scope[F]] {

    private[fs2] def step[F2[x] >: F[x]: Sync, O2 >: INothing, R2 >: Scope[F]](
        scope: CompileScope[F2]): F2[Either[R2, (Chunk[O2], Pull[F2, O2, R2])]] =
      Either
        .left[R2, (Chunk[O2], Pull[F2, O2, R2])](scope.asInstanceOf[Scope[F]])
        .pure[F2] // TODO make Scope covariant in F

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
      def suspend[R](p: => Pull[F, O, R]) = ??? // TODO Pull.suspend(p)
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

final class Stream[+F[_], +O] private (private val asPull: Pull[F, O, Unit]) extends AnyVal {

  /**
    * Appends `s2` to the end of this stream.
    * @example {{{
    * scala> ( Stream(1,2,3)++Stream(4,5,6) ).toList
    * res0: List[Int] = List(1, 2, 3, 4, 5, 6)
    * }}}
    *
    * If `this` stream is not terminating, then the result is equivalent to `this`.
    */
  def ++[F2[x] >: F[x], O2 >: O](s2: => Stream[F2, O2]): Stream[F2, O2] = append(s2)

  /** Appends `s2` to the end of this stream. Alias for `s1 ++ s2`. */
  def append[F2[x] >: F[x], O2 >: O](s2: => Stream[F2, O2]): Stream[F2, O2] =
    Stream.fromPull(asPull >> s2.asPull)

  def compile[F2[x] >: F[x], G[_], O2 >: O](
      implicit compiler: Stream.Compiler[F2, G]): Stream.CompileOps[F2, G, O2] =
    new Stream.CompileOps[F2, G, O2](asPull)

  /**
    * Prepends a chunk onto the front of this stream.
    *
    * @example {{{
    * scala> Stream(1,2,3).cons(Chunk(-1, 0)).toList
    * res0: List[Int] = List(-1, 0, 1, 2, 3)
    * }}}
    */
  def cons[O2 >: O](c: Chunk[O2]): Stream[F, O2] =
    if (c.isEmpty) this else Stream.chunk(c) ++ this

  def covary[F2[x] >: F[x]]: Stream[F2, O] = this

  /**
    * Lifts this stream to the specified effect and output types.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> Stream.empty.covaryAll[IO,Int]
    * res0: Stream[IO,Int] = Stream(..)
    * }}}
    */
  def covaryAll[F2[x] >: F[x], O2 >: O]: Stream[F2, O2] = this

  /**
    * Lifts this stream to the specified output type.
    *
    * @example {{{
    * scala> Stream(Some(1), Some(2), Some(3)).covaryOutput[Option[Int]]
    * res0: Stream[Pure,Option[Int]] = Stream(..)
    * }}}
    */
  def covaryOutput[O2 >: O]: Stream[F, O2] = this

  /**
    * Alias for `flatMap(o => Stream.eval(f(o)))`.
    *
    * @example {{{
    * scala> import cats.effect.IO
    * scala> Stream(1,2,3,4).evalMap(i => IO(println(i))).compile.drain.unsafeRunSync
    * res0: Unit = ()
    * }}}
    */
  def evalMap[F2[x] >: F[x], O2](f: O => F2[O2]): Stream[F2, O2] =
    flatMap(o => Stream.eval(f(o)))

  def flatMap[F2[x] >: F[x], O2](f: O => Stream[F2, O2]): Stream[F2, O2] =
    Stream.fromPull(Pull.mapConcat(asPull: Pull[F2, O, Unit])(o => f(o).asPull))

  /**
    * If `this` terminates with `Stream.raiseError(e)`, invoke `h(e)`.
    *
    * @example {{{
    * scala> Stream(1, 2, 3).append(Stream.raiseError[cats.effect.IO](new RuntimeException)).handleErrorWith(t => Stream(0)).compile.toList.unsafeRunSync()
    * res0: List[Int] = List(1, 2, 3, 0)
    * }}}
    */
  def handleErrorWith[F2[x] >: F[x], O2 >: O](h: Throwable => Stream[F2, O2]): Stream[F2, O2] =
    Stream.fromPull(asPull.scope.handleErrorWith(e => h(e).asPull))

  /**
    * Applies the specified pure function to each input and emits the result.
    *
    * @example {{{
    * scala> Stream("Hello", "World!").map(_.size).toList
    * res0: List[Int] = List(5, 6)
    * }}}
    */
  def map[O2](f: O => O2): Stream[F, O2] =
    this.pull.echo.mapOutput(f).streamNoScope

  /**
    * Repeat this stream an infinite number of times.
    *
    * `s.repeat == s ++ s ++ s ++ ...`
    *
    * @example {{{
    * scala> Stream(1,2,3).repeat.take(8).toList
    * res0: List[Int] = List(1, 2, 3, 1, 2, 3, 1, 2)
    * }}}
    */
  def repeat: Stream[F, O] =
    this ++ repeat

  def scope: Stream[F, O] = Stream.fromPull(asPull.scope)

  /**
    * Emits the first `n` elements of this stream.
    *
    * @example {{{
    * scala> Stream.range(0,1000).take(5).toList
    * res0: List[Int] = List(0, 1, 2, 3, 4)
    * }}}
    */
  def take(n: Long): Stream[F, O] = this.pull.take(n).stream

  def translate[F2[x] >: F[x], G[_]](u: F2 ~> G): Stream[G, O] =
    Stream.fromPull(asPull.translate(u))

  private type ZipWithCont[G[_], I, O2, R] =
    Either[(Chunk[I], Stream[G, I]), Stream[G, I]] => Pull[G, O2, Option[R]]

  private def zipWith_[F2[x] >: F[x], O2 >: O, O3, O4](that: Stream[F2, O3])(
      k1: ZipWithCont[F2, O2, O4, INothing],
      k2: ZipWithCont[F2, O3, O4, INothing])(f: (O2, O3) => O4): Stream[F2, O4] = {
    def go(leg1: Stream.StepLeg[F2, O2],
           leg2: Stream.StepLeg[F2, O3]): Pull[F2, O4, Option[INothing]] = {
      val l1h = leg1.head
      val l2h = leg2.head
      val out = l1h.zipWith(l2h)(f)
      Pull.output(out) >> {
        if (l1h.size > l2h.size) {
          val extra1 = l1h.drop(l2h.size)
          leg2.stepLeg.flatMap {
            case None      => k1(Left((extra1, leg1.stream)))
            case Some(tl2) => go(leg1.setHead(extra1), tl2)
          }
        } else {
          val extra2 = l2h.drop(l1h.size)
          leg1.stepLeg.flatMap {
            case None      => k2(Left((extra2, leg2.stream)))
            case Some(tl1) => go(tl1, leg2.setHead(extra2))
          }
        }
      }
    }

    covaryAll[F2, O2].pull.stepLeg.flatMap {
      case Some(leg1) =>
        that.pull.stepLeg
          .flatMap {
            case Some(leg2) => go(leg1, leg2)
            case None       => k1(Left((leg1.head, leg1.stream)))
          }

      case None => k2(Right(that))
    }.stream
  }

  /**
    * Determinsitically zips elements, terminating when the ends of both branches
    * are reached naturally, padding the left branch with `pad1` and padding the right branch
    * with `pad2` as necessary.
    *
    *
    * @example {{{
    * scala> Stream(1,2,3).zipAll(Stream(4,5,6,7))(0,0).toList
    * res0: List[(Int,Int)] = List((1,4), (2,5), (3,6), (0,7))
    * }}}
    */
  def zipAll[F2[x] >: F[x], O2 >: O, O3](that: Stream[F2, O3])(pad1: O2,
                                                               pad2: O3): Stream[F2, (O2, O3)] =
    zipAllWith[F2, O2, O3, (O2, O3)](that)(pad1, pad2)(Tuple2.apply)

  /**
    * Determinsitically zips elements with the specified function, terminating
    * when the ends of both branches are reached naturally, padding the left
    * branch with `pad1` and padding the right branch with `pad2` as necessary.
    *
    * @example {{{
    * scala> Stream(1,2,3).zipAllWith(Stream(4,5,6,7))(0, 0)(_ + _).toList
    * res0: List[Int] = List(5, 7, 9, 7)
    * }}}
    */
  def zipAllWith[F2[x] >: F[x], O2 >: O, O3, O4](that: Stream[F2, O3])(pad1: O2, pad2: O3)(
      f: (O2, O3) => O4): Stream[F2, O4] = {
    def cont1(
        z: Either[(Chunk[O2], Stream[F2, O2]), Stream[F2, O2]]): Pull[F2, O4, Option[INothing]] = {
      def contLeft(s: Stream[F2, O2]): Pull[F2, O4, Option[INothing]] =
        s.pull.uncons.flatMap {
          case None => Pull.pure(None)
          case Some((hd, tl)) =>
            Pull.output(hd.map(o => f(o, pad2))) >> contLeft(tl)
        }
      z match {
        case Left((hd, tl)) =>
          Pull.output(hd.map(o => f(o, pad2))) >> contLeft(tl)
        case Right(h) => contLeft(h)
      }
    }
    def cont2(
        z: Either[(Chunk[O3], Stream[F2, O3]), Stream[F2, O3]]): Pull[F2, O4, Option[INothing]] = {
      def contRight(s: Stream[F2, O3]): Pull[F2, O4, Option[INothing]] =
        s.pull.uncons.flatMap {
          case None => Pull.pure(None)
          case Some((hd, tl)) =>
            Pull.output(hd.map(o2 => f(pad1, o2))) >> contRight(tl)
        }
      z match {
        case Left((hd, tl)) =>
          Pull.output(hd.map(o2 => f(pad1, o2))) >> contRight(tl)
        case Right(h) => contRight(h)
      }
    }
    zipWith_[F2, O2, O3, O4](that)(cont1, cont2)(f)
  }

  /**
    * Determinsitically zips elements, terminating when the end of either branch is reached naturally.
    *
    * @example {{{
    * scala> Stream(1, 2, 3).zip(Stream(4, 5, 6, 7)).toList
    * res0: List[(Int,Int)] = List((1,4), (2,5), (3,6))
    * }}}
    */
  def zip[F2[x] >: F[x], O2](that: Stream[F2, O2]): Stream[F2, (O, O2)] =
    zipWith(that)(Tuple2.apply)

  /**
    * Like `zip`, but selects the right values only.
    * Useful with timed streams, the example below will emit a number every 100 milliseconds.
    *
    * @example {{{
    * scala> import scala.concurrent.duration._, cats.effect.{ContextShift, IO, Timer}
    * scala> implicit val cs: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.Implicits.global)
    * scala> implicit val timer: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.Implicits.global)
    * scala> val s = Stream.fixedDelay(100.millis) zipRight Stream.range(0, 5)
    * scala> s.compile.toVector.unsafeRunSync
    * res0: Vector[Int] = Vector(0, 1, 2, 3, 4)
    * }}}
    */
  def zipRight[F2[x] >: F[x], O2](that: Stream[F2, O2]): Stream[F2, O2] =
    zipWith(that)((_, y) => y)

  /**
    * Like `zip`, but selects the left values only.
    * Useful with timed streams, the example below will emit a number every 100 milliseconds.
    *
    * @example {{{
    * scala> import scala.concurrent.duration._, cats.effect.{ContextShift, IO, Timer}
    * scala> implicit val cs: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.Implicits.global)
    * scala> implicit val timer: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.Implicits.global)
    * scala> val s = Stream.range(0, 5) zipLeft Stream.fixedDelay(100.millis)
    * scala> s.compile.toVector.unsafeRunSync
    * res0: Vector[Int] = Vector(0, 1, 2, 3, 4)
    * }}}
    */
  def zipLeft[F2[x] >: F[x], O2](that: Stream[F2, O2]): Stream[F2, O] =
    zipWith(that)((x, _) => x)

  /**
    * Determinsitically zips elements using the specified function,
    * terminating when the end of either branch is reached naturally.
    *
    * @example {{{
    * scala> Stream(1, 2, 3).zipWith(Stream(4, 5, 6, 7))(_ + _).toList
    * res0: List[Int] = List(5, 7, 9)
    * }}}
    */
  def zipWith[F2[x] >: F[x], O2 >: O, O3, O4](that: Stream[F2, O3])(
      f: (O2, O3) => O4): Stream[F2, O4] =
    zipWith_[F2, O2, O3, O4](that)(sh => Pull.pure(None), h => Pull.pure(None))(f)

}

object Stream {
  private[fs2] def fromPull[F[_], O](p: Pull[F, O, Unit]): Stream[F, O] = new Stream(p)

  def apply[O](os: O*): Stream[Pure, O] = emits(os)

  /**
    * Creates a stream that emits a resource allocated by an effect, ensuring the resource is
    * eventually released regardless of how the stream is used.
    *
    * A typical use case for bracket is working with files or network sockets. The resource effect
    * opens a file and returns a reference to it. One can then flatMap on the returned Stream to access
    *  the file, e.g to read bytes and transform them in to some stream of elements
    * (e.g., bytes, strings, lines, etc.).
    * The `release` action then closes the file once the result Stream terminates, even in case of interruption
    * or errors.
    *
    * @param acquire resource to acquire at start of stream
    * @param release function which returns an effect that releases the resource
    */
  def bracket[F[x] >: Pure[x], R](acquire: F[R])(release: R => F[Unit]): Stream[F, R] =
    bracketCase(acquire)((r, _) => release(r))

  /**
    * Like [[bracket]] but the release action is passed an `ExitCase[Throwable]`.
    *
    * `ExitCase.Canceled` is passed to the release action in the event of either stream interruption or
    * overall compiled effect cancelation.
    */
  def bracketCase[F[x] >: Pure[x], R](acquire: F[R])(
      release: (R, ExitCase[Throwable]) => F[Unit]): Stream[F, R] =
    Stream.fromPull(Pull.acquireWithToken(acquire, release).flatMap {
      case (r, token) => Pull.output1(r) >> Pull.release(token)
    })

  def chunk[O](c: Chunk[O]): Stream[Pure, O] = fromPull(Pull.output(c))

  def emit[O](o: O): Stream[Pure, O] = fromPull(Pull.output1(o))

  def emits[O](os: Seq[O]): Stream[Pure, O] =
    if (os.isEmpty) empty
    else if (os.size == 1) emit(os.head)
    else fromPull(Pull.output(Chunk.seq(os)))

  val empty: Stream[Pure, Nothing] = fromPull(Pull.done)

  def eval[F[_], O](fo: F[O]): Stream[F, O] =
    fromPull(Pull.eval(fo).flatMap(o => Pull.output1(o)))

  def raiseError[F[_]: RaiseThrowable](e: Throwable): Stream[F, INothing] =
    fromPull(Pull.raiseError(e))

  /** Provides syntax for streams that are invariant in `F` and `O`. */
  implicit def InvariantOps[F[_], O](s: Stream[F, O]): InvariantOps[F, O] =
    new InvariantOps(s.asPull)

  /** Provides syntax for streams that are invariant in `F` and `O`. */
  final class InvariantOps[F[_], O] private[Stream] (private val asPull: Pull[F, O, Unit])
      extends AnyVal {
    private def self: Stream[F, O] = Stream.fromPull(asPull)

    /**
      * Lifts this stream to the specified effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream(1, 2, 3).covary[IO]
      * res0: Stream[IO,Int] = Stream(..)
      * }}}
      */
    def covary[F2[x] >: F[x]]: Stream[F2, O] = self

    // /**
    //   * Synchronously sends values through `sink`.
    //   *
    //   * If `sink` fails, then resulting stream will fail. If sink `halts` the evaluation will halt too.
    //   *
    //   * Note that observe will only output full chunks of `O` that are known to be successfully processed
    //   * by `sink`. So if Sink terminates/fail in middle of chunk processing, the chunk will not be available
    //   * in resulting stream.
    //   *
    //   * Note that if your sink can be represented by an `O => F[Unit]`, `evalTap` will provide much greater performance.
    //   *
    //   * @example {{{
    //   * scala> import cats.effect.{ContextShift, IO}, cats.implicits._
    //   * scala> implicit val cs: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.Implicits.global)
    //   * scala> Stream(1, 2, 3).covary[IO].observe(Sink.showLinesStdOut).map(_ + 1).compile.toVector.unsafeRunSync
    //   * res0: Vector[Int] = Vector(2, 3, 4)
    //   * }}}
    //   */
    // def observe(sink: Sink[F, O])(implicit F: Concurrent[F]): Stream[F, O] =
    //   observeAsync(1)(sink)

    // /** Send chunks through `sink`, allowing up to `maxQueued` pending _chunks_ before blocking `s`. */
    // def observeAsync(maxQueued: Int)(sink: Sink[F, O])(implicit F: Concurrent[F]): Stream[F, O] =
    //   Stream.eval(Semaphore[F](maxQueued - 1)).flatMap { guard =>
    //     Stream.eval(Queue.unbounded[F, Option[Chunk[O]]]).flatMap { outQ =>
    //       Stream.eval(Queue.unbounded[F, Option[Chunk[O]]]).flatMap { sinkQ =>
    //         def inputStream =
    //           self.chunks.noneTerminate.evalMap {
    //             case Some(chunk) =>
    //               sinkQ.enqueue1(Some(chunk)) >>
    //                 guard.acquire

    //             case None =>
    //               sinkQ.enqueue1(None)
    //           }

    //         def sinkStream =
    //           sinkQ.dequeue.unNoneTerminate
    //             .flatMap { chunk =>
    //               Stream.chunk(chunk) ++
    //                 Stream.eval_(outQ.enqueue1(Some(chunk)))
    //             }
    //             .to(sink) ++
    //             Stream.eval_(outQ.enqueue1(None))

    //         def runner =
    //           sinkStream.concurrently(inputStream) ++
    //             Stream.eval_(outQ.enqueue1(None))

    //         def outputStream =
    //           outQ.dequeue.unNoneTerminate
    //             .flatMap { chunk =>
    //               Stream.chunk(chunk) ++
    //                 Stream.eval_(guard.release)
    //             }

    //         outputStream.concurrently(runner)
    //       }
    //     }
    //   }

    /** Gets a projection of this stream that allows converting it to a `Pull` in a number of ways. */
    def pull: ToPull[F, O] = new ToPull[F, O](asPull)

    // /**
    //   * Repeatedly invokes `using`, running the resultant `Pull` each time, halting when a pull
    //   * returns `None` instead of `Some(nextStream)`.
    //   */
    // def repeatPull[O2](
    //     using: Stream.ToPull[F, O] => Pull[F, O2, Option[Stream[F, O]]]): Stream[F, O2] =
    //   Pull.loop(using.andThen(_.map(_.map(_.pull))))(pull).stream

  }

  /** Provides syntax for pure streams. */
  implicit def PureOps[O](s: Stream[Pure, O]): PureOps[O] = new PureOps(s.asPull)

  /** Provides syntax for pure streams. */
  final class PureOps[O] private[Stream] (private val asPull: Pull[Pure, O, Unit]) extends AnyVal {
    private def self: Stream[Pure, O] = Stream.fromPull(asPull)

    /** Alias for covary, to be able to write `Stream.empty[X]`. */
    def apply[F[_]]: Stream[F, O] = covary

    /** Lifts this stream to the specified effect type. */
    def covary[F[_]]: Stream[F, O] = self

    /** Runs this pure stream and returns the emitted elements in a collection of the specified type. Note: this method is only available on pure streams. */
    def to[C[_]](implicit f: Factory[O, C[O]]): C[O] =
      self.covary[IO].compile.to[C].unsafeRunSync

    /** Runs this pure stream and returns the emitted elements in a chunk. Note: this method is only available on pure streams. */
    def toChunk: Chunk[O] = self.covary[IO].compile.toChunk.unsafeRunSync

    /** Runs this pure stream and returns the emitted elements in a list. Note: this method is only available on pure streams. */
    def toList: List[O] = self.covary[IO].compile.toList.unsafeRunSync

    /** Runs this pure stream and returns the emitted elements in a vector. Note: this method is only available on pure streams. */
    def toVector: Vector[O] = self.covary[IO].compile.toVector.unsafeRunSync
  }

  /** Provides syntax for streams with effect type `cats.Id`. */
  implicit def IdOps[O](s: Stream[Id, O]): IdOps[O] = new IdOps(s.asPull)

  /** Provides syntax for pure pipes based on `cats.Id`. */
  final class IdOps[O] private[Stream] (private val asPull: Pull[Id, O, Unit]) extends AnyVal {
    private def self: Stream[Id, O] = Stream.fromPull(asPull)

    private def idToApplicative[F[_]: Applicative]: Id ~> F =
      new (Id ~> F) { def apply[A](a: Id[A]) = a.pure[F] }

    def covaryId[F[_]: Applicative]: Stream[F, O] = self.translate(idToApplicative[F])
  }

  /** Provides syntax for streams with effect type `Fallible`. */
  implicit def FallibleOps[O](s: Stream[Fallible, O]): FallibleOps[O] =
    new FallibleOps(s.asPull)

  /** Provides syntax for fallible streams. */
  final class FallibleOps[O] private[Stream] (private val asPull: Pull[Fallible, O, Unit])
      extends AnyVal {
    private def self: Stream[Fallible, O] = Stream.fromPull(asPull)

    /** Lifts this stream to the specified effect type. */
    def lift[F[_]](implicit F: ApplicativeError[F, Throwable]): Stream[F, O] = {
      val _ = F
      self.asInstanceOf[Stream[F, O]]
    }

    /** Runs this fallible stream and returns the emitted elements in a collection of the specified type. Note: this method is only available on fallible streams. */
    def to[C[_]](implicit f: Factory[O, C[O]]): Either[Throwable, C[O]] =
      lift[IO].compile.to[C].attempt.unsafeRunSync

    /** Runs this fallible stream and returns the emitted elements in a chunk. Note: this method is only available on fallible streams. */
    def toChunk: Either[Throwable, Chunk[O]] = lift[IO].compile.toChunk.attempt.unsafeRunSync

    /** Runs this fallible stream and returns the emitted elements in a list. Note: this method is only available on fallible streams. */
    def toList: Either[Throwable, List[O]] = lift[IO].compile.toList.attempt.unsafeRunSync

    /** Runs this fallible stream and returns the emitted elements in a vector. Note: this method is only available on fallible streams. */
    def toVector: Either[Throwable, Vector[O]] =
      lift[IO].compile.toVector.attempt.unsafeRunSync
  }

  /** Projection of a `Stream` providing various ways to get a `Pull` from the `Stream`. */
  final class ToPull[F[_], O] private[Stream] (private val self: Pull[F, O, Unit]) extends AnyVal {

    /**
      * Waits for a chunk of elements to be available in the source stream.
      * The chunk of elements along with a new stream are provided as the resource of the returned pull.
      * The new stream can be used for subsequent operations, like awaiting again.
      * A `None` is returned as the resource of the pull upon reaching the end of the stream.
      */
    def uncons: Pull[F, INothing, Option[(Chunk[O], Stream[F, O])]] =
      self.uncons.map {
        case Right((hd, tl)) => Some((hd, Stream.fromPull(tl)))
        case Left(_)         => None
      }

    /** Like [[uncons]] but waits for a single element instead of an entire chunk. */
    def uncons1: Pull[F, INothing, Option[(O, Stream[F, O])]] =
      uncons.flatMap {
        case None => Pull.pure(None)
        case Some((hd, tl)) =>
          hd.size match {
            case 0 => tl.pull.uncons1
            case 1 => Pull.pure(Some(hd(0) -> tl))
            case n => Pull.pure(Some(hd(0) -> tl.cons(hd.drop(1))))
          }
      }

    /** Writes all inputs to the output of the returned `Pull`. */
    def echo: Pull[F, O, Unit] = self

    /**
      * Like `uncons`, but instead of performing normal `uncons`, this will
      * run the stream up to the first chunk available.
      * Useful when zipping multiple streams (legs) into one stream.
      * Assures that scopes are correctly held for each stream `leg`
      * independently of scopes from other legs.
      *
      * If you are not pulling from multiple streams, consider using `uncons`.
      */
    def stepLeg: Pull[F, INothing, Option[StepLeg[F, O]]] =
      Pull
        .getScope[F]
        .flatMap { scope =>
          new StepLeg[F, O](Chunk.empty, scope.asInstanceOf[CompileScope[F]].id, self).stepLeg
        }

    /** Emits the first `n` elements of the input. */
    def take(n: Long): Pull[F, O, Option[Stream[F, O]]] =
      if (n <= 0) Pull.pure(None)
      else
        uncons.flatMap {
          case None => Pull.pure(None)
          case Some((hd, tl)) =>
            hd.size.toLong match {
              case m if m < n  => Pull.output(hd) >> tl.pull.take(n - m)
              case m if m == n => Pull.output(hd).as(Some(tl))
              case m =>
                val (pfx, sfx) = hd.splitAt(n.toInt)
                Pull.output(pfx).as(Some(tl.cons(sfx)))
            }
        }

  }

  /** Type class which describes compilation of a `Stream[F, O]` to a `G[?]`. */
  sealed trait Compiler[F[_], G[_]] {
    private[Stream] def apply[O, B, C](s: Stream[F, O], init: () => B)(fold: (B, Chunk[O]) => B,
                                                                       finalize: B => C): G[C]
  }

  object Compiler {
    implicit def syncInstance[F[_]](implicit F: Sync[F]): Compiler[F, F] = new Compiler[F, F] {
      def apply[O, B, C](s: Stream[F, O], init: () => B)(foldChunk: (B, Chunk[O]) => B,
                                                         finalize: B => C): F[C] =
        F.delay(init()).flatMap(i => s.asPull.compile(i)(foldChunk)).map {
          case (b, _) => finalize(b)
        }
    }

    implicit val pureInstance: Compiler[Pure, Id] = new Compiler[Pure, Id] {
      def apply[O, B, C](s: Stream[Pure, O], init: () => B)(foldChunk: (B, Chunk[O]) => B,
                                                            finalize: B => C): C =
        finalize(s.covary[IO].asPull.compile(init())(foldChunk).unsafeRunSync._1)
    }

    implicit val idInstance: Compiler[Id, Id] = new Compiler[Id, Id] {
      def apply[O, B, C](s: Stream[Id, O], init: () => B)(foldChunk: (B, Chunk[O]) => B,
                                                          finalize: B => C): C =
        finalize(s.covaryId[IO].asPull.compile(init())(foldChunk).unsafeRunSync._1)
    }

    implicit val fallibleInstance: Compiler[Fallible, Either[Throwable, ?]] =
      new Compiler[Fallible, Either[Throwable, ?]] {
        def apply[O, B, C](s: Stream[Fallible, O], init: () => B)(
            foldChunk: (B, Chunk[O]) => B,
            finalize: B => C): Either[Throwable, C] =
          s.lift[IO].asPull.compile(init())(foldChunk).attempt.unsafeRunSync.map {
            case (b, _) => finalize(b)
          }
      }
  }

  /** Projection of a `Stream` providing various ways to compile a `Stream[F,O]` to an `F[...]`. */
  final class CompileOps[F[_], G[_], O] private[Stream] (private val self: Pull[F, O, Unit])(
      implicit compiler: Compiler[F, G]) {

    /**
      * Compiles this stream in to a value of the target effect type `F` and
      * discards any output values of the stream.
      *
      * To access the output values of the stream, use one of the other compilation methods --
      * e.g., [[fold]], [[toVector]], etc.
      */
    def drain: G[Unit] = foldChunks(())((_, _) => ())

    /**
      * Compiles this stream in to a value of the target effect type `F` by folding
      * the output values together, starting with the provided `init` and combining the
      * current value with each output value.
      */
    def fold[B](init: B)(f: (B, O) => B): G[B] =
      foldChunks(init)((acc, c) => c.foldLeft(acc)(f))

    /**
      * Compiles this stream in to a value of the target effect type `F` by folding
      * the output chunks together, starting with the provided `init` and combining the
      * current value with each output chunk.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      */
    def foldChunks[B](init: B)(f: (B, Chunk[O]) => B): G[B] =
      compiler(fromPull(self), () => init)(f, identity)

    /**
      * Like [[fold]] but uses the implicitly available `Monoid[O]` to combine elements.
      *
      * @example {{{
      * scala> import cats.implicits._, cats.effect.IO
      * scala> Stream(1, 2, 3, 4, 5).covary[IO].compile.foldMonoid.unsafeRunSync
      * res0: Int = 15
      * }}}
      */
    def foldMonoid(implicit O: Monoid[O]): G[O] =
      fold(O.empty)(O.combine)

    /**
      * Like [[fold]] but uses the implicitly available `Semigroup[O]` to combine elements.
      * If the stream emits no elements, `None` is returned.
      *
      * @example {{{
      * scala> import cats.implicits._, cats.effect.IO
      * scala> Stream(1, 2, 3, 4, 5).covary[IO].compile.foldSemigroup.unsafeRunSync
      * res0: Option[Int] = Some(15)
      * scala> Stream.empty.covaryAll[IO, Int].compile.foldSemigroup.unsafeRunSync
      * res1: Option[Int] = None
      * }}}
      */
    def foldSemigroup(implicit O: Semigroup[O]): G[Option[O]] =
      fold(Option.empty[O])((acc, o) => acc.map(O.combine(_, o)).orElse(Some(o)))

    /**
      * Compiles this stream in to a value of the target effect type `F`,
      * returning `None` if the stream emitted no values and returning the
      * last value emitted wrapped in `Some` if values were emitted.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.last.unsafeRunSync
      * res0: Option[Int] = Some(4)
      * }}}
      */
    def last: G[Option[O]] =
      foldChunks(Option.empty[O])((acc, c) => c.last.orElse(acc))

    /**
      * Compiles this stream in to a value of the target effect type `F`,
      * raising a `NoSuchElementException` if the stream emitted no values
      * and returning the last value emitted otherwise.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.lastOrError.unsafeRunSync
      * res0: Int = 4
      * scala> Stream.empty.covaryAll[IO, Int].compile.lastOrError.attempt.unsafeRunSync
      * res1: Either[Throwable, Int] = Left(java.util.NoSuchElementException)
      * }}}
      */
    def lastOrError(implicit G: MonadError[G, Throwable]): G[O] =
      last.flatMap(_.fold(G.raiseError(new NoSuchElementException): G[O])(G.pure))

    /**
      * Compiles this stream into a value of the target effect type `F` by logging
      * the output values to a `C`, given a `Factory`.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.to[List].unsafeRunSync
      * res0: List[Int] = List(0, 1, 2, 3, 4)
      * }}}
      */
    def to[C[_]](implicit f: Factory[O, C[O]]): G[C[O]] =
      compiler(fromPull(self), () => f.newBuilder)(_ ++= _.iterator, _.result)

    /**
      * Compiles this stream in to a value of the target effect type `F` by logging
      * the output values to a `Chunk`.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.toChunk.unsafeRunSync
      * res0: Chunk[Int] = Chunk(0, 1, 2, 3, 4)
      * }}}
      */
    def toChunk: G[Chunk[O]] =
      compiler(Stream.fromPull(self), () => List.newBuilder[Chunk[O]])(
        _ += _,
        bldr => Chunk.concat(bldr.result))

    /**
      * Compiles this stream in to a value of the target effect type `F` by logging
      * the output values to a `List`. Equivalent to `to[List]`.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.toList.unsafeRunSync
      * res0: List[Int] = List(0, 1, 2, 3, 4)
      * }}}
      */
    def toList: G[List[O]] =
      to[List]

    /**
      * Compiles this stream in to a value of the target effect type `F` by logging
      * the output values to a `Vector`. Equivalent to `to[Vector]`.
      *
      * When this method has returned, the stream has not begun execution -- this method simply
      * compiles the stream down to the target effect type.
      *
      * @example {{{
      * scala> import cats.effect.IO
      * scala> Stream.range(0,100).take(5).covary[IO].compile.toVector.unsafeRunSync
      * res0: Vector[Int] = Vector(0, 1, 2, 3, 4)
      * }}}
      */
    def toVector: G[Vector[O]] =
      to[Vector]
  }

  /**
    * When merging multiple streams, this represents step of one leg.
    *
    * It is common to `uncons`, however unlike `uncons`, it keeps track
    * of stream scope independently of the main scope of the stream.
    *
    * This assures, that after each next `stepLeg` each Stream `leg` keeps its scope
    * when interpreting.
    *
    * Usual scenarios is to first invoke `stream.pull.stepLeg` and then consume whatever is
    * available in `leg.head`. If the next step is required `leg.stepLeg` will yield next `Leg`.
    *
    * Once the stream will stop to be interleaved (merged), then `stream` allows to return to normal stream
    * invocation.
    *
    */
  final class StepLeg[F[_], O](
      val head: Chunk[O],
      private[fs2] val scopeId: Token,
      private[fs2] val next: Pull[F, O, Unit]
  ) { self =>

    /**
      * Converts this leg back to regular stream. Scope is updated to the scope associated with this leg.
      * Note that when this is invoked, no more interleaving legs are allowed, and this must be very last
      * leg remaining.
      *
      * Note that resulting stream won't contain the `head` of this leg.
      */
    def stream: Stream[F, O] =
      Pull
        .loop[F, O, StepLeg[F, O]] { leg =>
          Pull.output(leg.head).flatMap(_ => leg.stepLeg)
        }(self.setHead(Chunk.empty))
        .stream

    /** Replaces head of this leg. Useful when the head was not fully consumed. */
    def setHead(nextHead: Chunk[O]): StepLeg[F, O] =
      new StepLeg[F, O](nextHead, scopeId, next)

    /** Provides an `uncons`-like operation on this leg of the stream. */
    def stepLeg: Pull[F, INothing, Option[StepLeg[F, O]]] =
      next.uncons
        .stepWith(scopeId)
        .map(_.map {
          case (hd, tl) => new StepLeg[F, O](hd, scopeId, tl)
        }.toOption)
  }
}
