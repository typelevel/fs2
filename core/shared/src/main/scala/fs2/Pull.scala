package fs2

import cats.effect.Sync

import fs2.util.{Attempt,Free,NonFatal,RealSupertype,Sub1}
import StreamCore.Token
import Pull._

/**
 * Allows acquiring elements from a stream in a resource safe way,
 * emitting elements of type `O`, working with a resource of type `R`,
 * and evaluating effects of type `F`.
 *
 * Laws:
 *
 * `or` forms a monoid in conjunction with `done`:
 *   - `or(done, p) == p` and `or(p, done) == p`.
 *   - `or(or(p1,p2), p3) == or(p1, or(p2,p3))`
 *
 * `fail` is caught by `onError`:
 *   - `onError(fail(e))(f) == f(e)`
 *
 * `Pull` forms a monad with `pure` and `flatMap`:
 *   - `pure >=> f == f`
 *   - `f >=> pure == f`
 *   - `(f >=> g) >=> h == f >=> (g >=> h)`
 * where `f >=> g` is defined as `a => a flatMap f flatMap g`
 */
final class Pull[+F[_],+O,+R] private (private val get: Free[AlgebraF[F,O]#f,Option[Attempt[R]]]) {

  private def close_(asStep: Boolean): Stream[F,O] = Stream.mk { val s = {
    type G[x] = StreamCore[F,O]; type Out = Option[Attempt[R]]
    get.fold[AlgebraF[F,O]#f,G,Out](new Free.Fold[AlgebraF[F,O]#f,G,Out] {
      def suspend(g: => G[Out]) = StreamCore.suspend(g)
      def done(o: Out) = o match {
        case None => StreamCore.empty
        case Some(e) => e.fold(StreamCore.fail, _ => StreamCore.empty)
      }
      def fail(t: Throwable) = StreamCore.fail(t)
      def eval[X](fx: AlgebraF[F,O]#f[X])(f: Attempt[X] => G[X]) = fx match {
        case Algebra.Eval(fr) => StreamCore.evalScope(fr.attempt).flatMap(f)
        case Algebra.Output(o) => StreamCore.append(o, StreamCore.suspend(f(Right(()))))
      }
      def bind[X](x: X)(f: X => G[Out]) = StreamCore.attemptStream(f(x))
    })(Sub1.sub1[AlgebraF[F,O]#f], implicitly[RealSupertype[Out,Out]])
  }; if (asStep) s else StreamCore.scope(s) }

  /** Interpret this `Pull` to produce a `Stream`. The result type `R` is discarded. */
  def close: Stream[F,O] = close_(false)

  /** Close this `Pull`, but don't cleanup any resources acquired. */
  private[fs2] def closeAsStep: Stream[F,O] = close_(true)

  /** Returns this pull's resource wrapped in `Some` or returns `None` if this pull fails due to an exhausted `Handle`. */
  def optional: Pull[F,O,Option[R]] =
    map(Some(_)).or(Pull.pure(None))

  /**
   * Consult `p2` if this pull fails due to an `await` on an exhausted `Handle`.
   * If this pull fails due to an error, `p2` is not consulted.
   */
  def or[F2[x]>:F[x],O2>:O,R2>:R](p2: => Pull[F2,O2,R2])(implicit S1: RealSupertype[O,O2], R2: RealSupertype[R,R2]): Pull[F2,O2,R2] = new Pull(
    get.flatMap[AlgebraF[F2,O2]#f,Option[Attempt[R2]]] {
      case Some(Right(r)) => Free.pure(Some(Right(r)))
      case None => attemptPull(p2).get
      case Some(Left(err)) => Free.pure(Some(Left(err)))
    }
  )

  /** Applies the resource of this pull to `f` and returns the result in a new `Pull`. */
  def map[R2](f: R => R2): Pull[F,O,R2] = new Pull(
    get.map {
      case Some(Right(r)) => Some(Right(f(r)))
      case None => None
      case Some(Left(err)) => Some(Left(err))
    }
  )

  /** Applies the resource of this pull to `f` and returns the result. */
  def flatMap[F2[x]>:F[x],O2>:O,R2](f: R => Pull[F2,O2,R2]): Pull[F2,O2,R2] = new Pull(
    get.flatMap[AlgebraF[F2,O2]#f,Option[Attempt[R2]]] {
      case Some(Right(r)) => attemptPull(f(r)).get
      case None => Free.pure(None)
      case Some(Left(err)) => Free.pure(Some(Left(err)))
    }
  )

  /** If `f` returns true when passed the resource of this pull, this pull is returned. Otherwise, `Pull.done` is returned. */
  def filter(f: R => Boolean): Pull[F,O,R] = withFilter(f)

  /** If `f` returns true when passed the resource of this pull, this pull is returned. Otherwise, `Pull.done` is returned. */
  def withFilter(f: R => Boolean): Pull[F,O,R] =
    flatMap(r => if (f(r)) Pull.pure(r) else Pull.done)

  /** Defined as `p >> p2 == p flatMap { _ => p2 }`. */
  def >>[F2[x]>:F[x],O2>:O,R2](p2: => Pull[F2,O2,R2])(implicit S: RealSupertype[O,O2]): Pull[F2,O2,R2] =
    flatMap { _ => p2 }

  /** Definition: `p as r == p map (_ => r)`. */
  def as[R2](r: R2): Pull[F,O,R2] = map (_ => r)

  def attempt: Pull[F,O,Attempt[R]] = new Pull(
    get.flatMap[AlgebraF[F,O]#f,Option[Attempt[Attempt[R]]]] {
      case None => Free.pure(None)
      case Some(e) => Free.pure(Some(e.map(Right(_))))
    })

  /** Converts this pull to a pull of the specified subtype. */
  implicit def covary[F2[_]](implicit S: Sub1[F,F2]): Pull[F2,O,R] = Sub1.substPull(this)

  override def toString = "Pull"
}

object Pull {

  private sealed trait Algebra[+F[_],+O,+R]
  private object Algebra {
    final case class Eval[F[_],O,R](f: Scope[F,R]) extends Algebra[F,O,R]
    final case class Output[F[_],O](s: StreamCore[F,O]) extends Algebra[F,O,Unit]
  }

  private sealed trait AlgebraF[F[_],O] { type f[x] = Algebra[F,O,x] }

  /** Result of `acquireCancellable`. */
  trait Cancellable[+F[_],+R] {
    val cancel: Pull[F,Nothing,Unit]
    val resource: R

    def map[R2](f: R => R2): Cancellable[F,R2]
  }
  object Cancellable {
    def apply[F[_],R](cancel0: Pull[F,Nothing,Unit], r: R): Cancellable[F,R] = new Cancellable[F,R] {
      val cancel = cancel0
      val resource = r
      def map[R2](f: R => R2): Cancellable[F,R2] = apply(cancel, f(r))
    }
  }

  /**
   * Acquire a resource within a `Pull`. The cleanup action will be run at the end
   * of the `.close` scope which executes the returned `Pull`. The acquired
   * resource is returned as the result value of the pull.
   */
  def acquire[F[_],R](r: F[R])(cleanup: R => F[Unit]): Pull[F,Nothing,R] =
    acquireCancellable(r)(cleanup).map(_.resource)

  /**
   * Like [[acquire]] but the result value consists of a cancellation
   * pull and the acquired resource. Running the cancellation pull frees the resource.
   * This allows the acquired resource to be released earlier than at the end of the
   * containing pull scope.
   */
  def acquireCancellable[F[_],R](r: F[R])(cleanup: R => F[Unit]): Pull[F,Nothing,Cancellable[F,R]] =
    Stream.bracketWithToken(r)(Stream.emit, cleanup).open.flatMap { h => h.await1.flatMap {
      case ((token, r), _) => Pull.pure(Cancellable(Pull.release(List(token)), r))
    }}

  /**
   * Creates a pull that when interpreted, evalutes the specified effectful value and returns
   * the result as the resource of the pull. If evaluating the effect results in an exception,
   * the exception is returned as a `Left`. Otherwise, the result is returned as a `Right`.
   */
  def attemptEval[F[_],R](f: F[R]): Pull[F,Nothing,Attempt[R]] =
    new Pull(Free.attemptEval[AlgebraF[F,Nothing]#f,R](Algebra.Eval(Scope.eval(f))).map(e => Some(Right(e))))

  private def attemptPull[F[_],O,R](p: => Pull[F,O,R]): Pull[F,O,R] =
    try p catch { case NonFatal(e) => fail(e) }

  /** The completed `Pull`. Reads and outputs nothing. */
  def done: Pull[Nothing,Nothing,Nothing] =
    new Pull(Free.pure(None))

  /**
   * Creates a pull that when interpreted, evalutes the specified effectful value and returns
   * the result as the resource of the pull. If evaluating the effect results in an exception,
   * the exception fails the pull (via [[fail]]).
   */
  def eval[F[_],R](f: F[R]): Pull[F,Nothing,R] =
    attemptEval(f) flatMap { _.fold(fail, pure) }

  /** Lifts a scope in to a pull. */
  def evalScope[F[_],R](f: Scope[F,R]): Pull[F,Nothing,R] =
    new Pull(Free.eval[AlgebraF[F,Nothing]#f,R](Algebra.Eval(f)).map(e => Some(Right(e))))

  /** The `Pull` that reads and outputs nothing, and fails with the given error. */
  def fail(err: Throwable): Pull[Nothing,Nothing,Nothing] =
    new Pull(Free.pure(Some(Left(err))))

  /**
   * Repeatedly use the output of the `Pull` as input for the next step of the pull.
   * Halts when a step terminates with `Pull.done` or `Pull.fail`.
   */
  def loop[F[_],W,R](using: R => Pull[F,W,R]): R => Pull[F,W,Nothing] =
    r => using(r) flatMap loop(using)

  /** If `p` terminates with `fail(e)`, invoke `handle(e)`. */
  def onError[F[_],O,R](p: Pull[F,O,R])(handle: Throwable => Pull[F,O,R]): Pull[F,O,R] =
    new Pull(
      p.get.flatMap[AlgebraF[F,O]#f,Option[Attempt[R]]] {
        case Some(Right(r)) => Free.pure(Some(Right(r)))
        case None => Free.pure(None)
        case Some(Left(err)) => attemptPull(handle(err)).get
      }
    )

  /** Write a `Chunk[W]` to the output of this `Pull`. */
  def output[F[_],W](w: Chunk[W]): Pull[F,W,Unit] = outputs(Stream.chunk(w))

  /** Write a single `W` to the output of this `Pull`. */
  def output1[F[_],W](w: W): Pull[F,W,Unit] = outputs(Stream.emit(w))

  /** Write a stream to the output of this `Pull`. */
  def outputs[F[_],O](s: Stream[F,O]): Pull[F,O,Unit] =
    new Pull(Free.eval[AlgebraF[F,O]#f,Unit](Algebra.Output(s.get)).map(_ => Some(Right(()))))

  /** The `Pull` that reads and outputs nothing, and succeeds with the given value, `R`. */
  def pure[R](r: R): Pull[Nothing,Nothing,R] =
    new Pull(Free.pure(Some(Right(r))))

  private[fs2] def release(ts: List[Token]): Pull[Nothing,Nothing,Unit] =
    outputs(Stream.mk(StreamCore.release(ts).drain))

  /** Returns a pull that lazily evaluates `p`. */
  def suspend[F[_],O,R](p: => Pull[F,O,R]): Pull[F,O,R] = Pull.pure(()) flatMap { _ => p }

  /** Converts a pure pull to an effectful pull of the specified type. */
  implicit def covaryPure[F[_],W,R](p: Pull[Pure,W,R]): Pull[F,W,R] = p.covary[F]

  // Note: non-implicit so that cats syntax doesn't override FS2 syntax
  def syncInstance[F[_],O]: Sync[Pull[F,O,?]] = new Sync[Pull[F,O,?]] {
    def pure[A](a: A): Pull[F,O,A] = Pull.pure(a)
    def handleErrorWith[A](p: Pull[F,O,A])(h: Throwable => Pull[F,O,A]) =
      p.attempt.flatMap {
        case Left(t) => h(t)
        case Right(a) => Pull.pure(a)
      }
    def raiseError[A](t: Throwable) = Pull.fail(t)
    def flatMap[A,B](p: Pull[F,O,A])(f: A => Pull[F,O,B]) = p.flatMap(f)
    def tailRecM[A, B](a: A)(f: A => Pull[F,O,Either[A,B]]) = f(a).flatMap {
      case Left(a) => tailRecM(a)(f)
      case Right(b) => Pull.pure(b)
    }
    def suspend[R](p: => Pull[F,O,R]) = Pull.suspend(p)
  }
}
