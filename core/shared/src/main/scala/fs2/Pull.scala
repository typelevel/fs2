package fs2

import cats.effect.Sync
import fs2.internal.{ Algebra, FreeC }

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
 *
 * @hideImplicitConversion covaryPure
 */
final class Pull[+F[_],+O,+R] private(private val free: FreeC[Algebra[Nothing,Nothing,?],R]) extends AnyVal {

  private[fs2] def get[F2[x]>:F[x],O2>:O,R2>:R]: FreeC[Algebra[F2,O2,?],R2] = free.asInstanceOf[FreeC[Algebra[F2,O2,?],R2]]

  /** Alias for `_.map(_ => o2)`. */
  def as[R2](r2: R2): Pull[F,O,R2] = map(_ => r2)

  /** Returns a pull with the result wrapped in `Right`, or an error wrapped in `Left` if the pull has failed. */
  def attempt: Pull[F,O,Either[Throwable,R]] =
    Pull.fromFreeC(get[F,O,R].map(r => Right(r)).handleErrorWith(t => FreeC.Pure(Left(t))))

  /** Interpret this `Pull` to produce a `Stream`. The result type `R` is discarded. */
  def stream: Stream[F,O] = Stream.fromFreeC(this.scope.get[F,O,R].map(_ => ()))

  /**
   * Like [[stream]] but no scope is inserted around the pull, resulting in any resources being
   * promoted to the parent scope of the stream, extending the resource lifetime. Typically used
   * as a performance optimization, where resource lifetime can be extended in exchange for faster
   * execution.
   */
  def streamNoScope: Stream[F,O] = Stream.fromFreeC(get[F,O,R].map(_ => ()))

  /** Applies the resource of this pull to `f` and returns the result. */
  def flatMap[F2[x]>:F[x],O2>:O,R2](f: R => Pull[F2,O2,R2]): Pull[F2,O2,R2] =
    Pull.fromFreeC(get[F2,O2,R] flatMap { r => f(r).get })

  /** Alias for `flatMap(_ => p2)`. */
  def *>[F2[x]>:F[x],O2>:O,R2](p2: => Pull[F2,O2,R2]): Pull[F2,O2,R2] =
    this flatMap { _ => p2 }

  /** Lifts this pull to the specified effect type. */
  def covary[F2[x]>:F[x]]: Pull[F2,O,R] = this.asInstanceOf[Pull[F2,O,R]]

  /** Lifts this pull to the specified effect type, output type, and resource type. */
  def covaryAll[F2[x]>:F[x],O2>:O,R2>:R]: Pull[F2,O2,R2] = this.asInstanceOf[Pull[F2,O2,R2]]

  /** Lifts this pull to the specified output type. */
  def covaryOutput[O2>:O]: Pull[F,O2,R] = this.asInstanceOf[Pull[F,O2,R]]

  /** Lifts this pull to the specified resource type. */
  def covaryResource[R2>:R]: Pull[F,O,R2] = this.asInstanceOf[Pull[F,O,R2]]

  /** Applies the resource of this pull to `f` and returns the result in a new `Pull`. */
  def map[R2](f: R => R2): Pull[F,O,R2] = Pull.fromFreeC(get map f)

  /** Run `p2` after `this`, regardless of errors during `this`, then reraise any errors encountered during `this`. */
  def onComplete[F2[x]>:F[x],O2>:O,R2>:R](p2: => Pull[F2,O2,R2]): Pull[F2,O2,R2] =
    handleErrorWith(e => p2 *> Pull.raiseError(e)) flatMap { _ =>  p2 }

  /** If `this` terminates with `Pull.raiseError(e)`, invoke `h(e)`. */
  def handleErrorWith[F2[x]>:F[x],O2>:O,R2>:R](h: Throwable => Pull[F2,O2,R2]): Pull[F2,O2,R2] =
    Pull.fromFreeC(get[F2,O2,R2] handleErrorWith { e => h(e).get })

  /** Tracks any resources acquired during this pull and releases them when the pull completes. */
  def scope: Pull[F,O,R] = Pull.fromFreeC(Algebra.scope(get))
}

object Pull {

  private[fs2] def fromFreeC[F[_],O,R](free: FreeC[Algebra[F,O,?],R]): Pull[F,O,R] =
    new Pull(free.asInstanceOf[FreeC[Algebra[Nothing,Nothing,?],R]])

  /** Result of `acquireCancellable`. */
  sealed abstract class Cancellable[+F[_],+R] {
    /** Cancels the cleanup of the resource (typically because the resource was manually cleaned up). */
    val cancel: Pull[F,Nothing,Unit]
    /** Acquired resource. */
    val resource: R

    /** Returns a new cancellable with the same `cancel` pull but with the resource returned from applying `R` to `f`. */
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
   * of the `.stream` scope which executes the returned `Pull`. The acquired
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
    Stream.bracketWithToken(r)(r => Stream.emit(r), cleanup).pull.uncons1.flatMap {
      case None => Pull.raiseError(new RuntimeException("impossible"))
      case Some(((token, r), tl)) => Pull.pure(Cancellable(release(token), r))
    }

  /**
   * Like [[eval]] but if the effectful value fails, the exception is returned in a `Left`
   * instead of failing the pull.
   */
  def attemptEval[F[_],R](fr: F[R]): Pull[F,Nothing,Either[Throwable,R]] =
    fromFreeC(
      Algebra.eval[F,Nothing,R](fr).
        map(r => Right(r): Either[Throwable,R]).
        handleErrorWith(t => Algebra.pure[F,Nothing,Either[Throwable,R]](Left(t))))

  /** The completed `Pull`. Reads and outputs nothing. */
  val done: Pull[Nothing,Nothing,Unit] = fromFreeC[Nothing,Nothing,Unit](Algebra.pure[Nothing,Nothing,Unit](()))

  /** Evaluates the supplied effectful value and returns the result as the resource of the returned pull. */
  def eval[F[_],R](fr: F[R]): Pull[F,Nothing,R] =
    fromFreeC(Algebra.eval[F,Nothing,R](fr))

  /**
   * Repeatedly uses the output of the pull as input for the next step of the pull.
   * Halts when a step terminates with `None` or `Pull.raiseError`.
   */
  def loop[F[_],O,R](using: R => Pull[F,O,Option[R]]): R => Pull[F,O,Option[R]] =
    r => using(r) flatMap { _.map(loop(using)).getOrElse(Pull.pure(None)) }

  /** Ouptuts a single value. */
  def output1[F[_],O](o: O): Pull[F,O,Unit] =
    fromFreeC(Algebra.output1[F,O](o))

  /** Ouptuts a segment of values. */
  def output[F[_],O](os: Segment[O,Unit]): Pull[F,O,Unit] =
    fromFreeC(Algebra.output[F,O](os))

  /** Pull that outputs nothing and has result of `r`. */
  def pure[F[_],R](r: R): Pull[F,Nothing,R] =
    fromFreeC(Algebra.pure(r))

  /** Reads and outputs nothing, and fails with the given error. */
  def raiseError(err: Throwable): Pull[Nothing,Nothing,Nothing] =
    new Pull(Algebra.raiseError[Nothing,Nothing,Nothing](err))

  /**
   * Pull that outputs the specified segment and returns the result of the segment as the result
   * of the pull. Less efficient than [[output]].
   */
  def segment[F[_],O,R](s: Segment[O,R]): Pull[F,O,R] =
    fromFreeC(Algebra.segment[F,O,R](s))

  /**
   * Returns a pull that evaluates the supplied by-name each time the pull is used,
   * allowing use of a mutable value in pull computations.
   */
  def suspend[F[_],O,R](p: => Pull[F,O,R]): Pull[F,O,R] =
    fromFreeC(Algebra.suspend(p.get))

  private def release[F[_]](token: Algebra.Token): Pull[F,Nothing,Unit] =
    fromFreeC[F,Nothing,Unit](Algebra.release(token))

  /** Implicitly covaries a pull. */
  implicit def covaryPure[F[_],O,R,O2>:O,R2>:R](p: Pull[Pure,O,R]): Pull[F,O2,R2] = p.asInstanceOf[Pull[F,O,R]]

  /** `Sync` instance for `Stream`. */
  implicit def syncInstance[F[_],O]: Sync[Pull[F,O,?]] = new Sync[Pull[F,O,?]] {
    def pure[A](a: A): Pull[F,O,A] = Pull.pure(a)
    def handleErrorWith[A](p: Pull[F,O,A])(h: Throwable => Pull[F,O,A]) = p.handleErrorWith(h)
    def raiseError[A](t: Throwable) = Pull.raiseError(t)
    def flatMap[A,B](p: Pull[F,O,A])(f: A => Pull[F,O,B]) = p.flatMap(f)
    def tailRecM[A, B](a: A)(f: A => Pull[F,O,Either[A,B]]) = f(a).flatMap {
      case Left(a) => tailRecM(a)(f)
      case Right(b) => Pull.pure(b)
    }
    def suspend[R](p: => Pull[F,O,R]) = Pull.suspend(p)
  }
}
