package fs2

import cats.{Eval => _, _}
import cats.arrow.FunctionK
import cats.effect._
import fs2.internal._
import fs2.internal.FreeC.Result
import fs2.internal.Algebra.Eval

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
final class Pull[+F[_], +O, +R] private[fs2] (private[fs2] val free: FreeC[F, O, R])
    extends AnyVal {
  private[fs2] def get[F2[x] >: F[x], O2 >: O, R2 >: R]: FreeC[F2, O2, R2] = free

  /** Alias for `_.map(_ => o2)`. */
  def as[R2](r2: R2): Pull[F, O, R2] = map(_ => r2)

  /** Returns a pull with the result wrapped in `Right`, or an error wrapped in `Left` if the pull has failed. */
  def attempt: Pull[F, O, Either[Throwable, R]] =
    new Pull(free.map(r => Right(r)).handleErrorWith(t => Result.Pure(Left(t))))

  /**
    * Interpret this `Pull` to produce a `Stream`.
    *
    * May only be called on pulls which return a `Unit` result type. Use `p.void.stream` to explicitly
    * ignore the result type of the pull.
    */
  def stream(implicit ev: R <:< Unit): Stream[F, O] = {
    val _ = ev
    Stream.fromFreeC(free.asInstanceOf[FreeC[F, O, Unit]])
  }

  /** Applies the resource of this pull to `f` and returns the result. */
  def flatMap[F2[x] >: F[x], O2 >: O, R2](f: R => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    new Pull(free.flatMap(r => f(r).free))

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
  def map[R2](f: R => R2): Pull[F, O, R2] = new Pull(free.map(f))

  /** Applies the outputs of this pull to `f` and returns the result in a new `Pull`. */
  def mapOutput[O2](f: O => O2): Pull[F, O2, R] = new Pull(free.mapOutput(f))

  /** Run `p2` after `this`, regardless of errors during `this`, then reraise any errors encountered during `this`. */
  def onComplete[F2[x] >: F[x], O2 >: O, R2 >: R](p2: => Pull[F2, O2, R2]): Pull[F2, O2, R2] =
    handleErrorWith(e => p2 >> new Pull(Result.Fail(e))) >> p2

  /** If `this` terminates with `Pull.raiseError(e)`, invoke `h(e)`. */
  def handleErrorWith[F2[x] >: F[x], O2 >: O, R2 >: R](
      h: Throwable => Pull[F2, O2, R2]
  ): Pull[F2, O2, R2] =
    new Pull(free.handleErrorWith(e => h(e).get))

  /** Discards the result type of this pull. */
  def void: Pull[F, O, Unit] = as(())
}

object Pull extends PullLowPriority {
  @inline private[fs2] def fromFreeC[F[_], O, R](free: FreeC[F, O, R]): Pull[F, O, R] =
    new Pull(free)

  /**
    * Like [[eval]] but if the effectful value fails, the exception is returned in a `Left`
    * instead of failing the pull.
    */
  def attemptEval[F[_], R](fr: F[R]): Pull[F, INothing, Either[Throwable, R]] =
    new Pull(
      Eval[F, R](fr)
        .map(r => Right(r): Either[Throwable, R])
        .handleErrorWith(t => Result.Pure[Either[Throwable, R]](Left(t)))
    )

  /** The completed `Pull`. Reads and outputs nothing. */
  val done: Pull[Pure, INothing, Unit] =
    new Pull(Result.unit)

  /** Evaluates the supplied effectful value and returns the result as the resource of the returned pull. */
  def eval[F[_], R](fr: F[R]): Pull[F, INothing, R] =
    new Pull(Eval[F, R](fr))

  /**
    * Repeatedly uses the output of the pull as input for the next step of the pull.
    * Halts when a step terminates with `None` or `Pull.raiseError`.
    */
  def loop[F[_], O, R](using: R => Pull[F, O, Option[R]]): R => Pull[F, O, Option[R]] =
    r => using(r).flatMap { _.map(loop(using)).getOrElse(Pull.pure(None)) }

  /** Outputs a single value. */
  def output1[F[x] >: Pure[x], O](o: O): Pull[F, O, Unit] =
    new Pull(Algebra.output1[O](o))

  /** Outputs a chunk of values. */
  def output[F[x] >: Pure[x], O](os: Chunk[O]): Pull[F, O, Unit] =
    new Pull(if (os.isEmpty) Result.unit else Algebra.Output[O](os))

  /** Pull that outputs nothing and has result of `r`. */
  def pure[F[x] >: Pure[x], R](r: R): Pull[F, INothing, R] =
    new Pull(Result.Pure(r))

  /**
    * Reads and outputs nothing, and fails with the given error.
    *
    * The `F` type must be explicitly provided (e.g., via `raiseError[IO]` or `raiseError[Fallible]`).
    */
  def raiseError[F[_]: RaiseThrowable](err: Throwable): Pull[F, INothing, INothing] =
    new Pull(Result.Fail(err))

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
    new Pull(FreeC.suspend(p.free))

  /** `Sync` instance for `Pull`. */
  implicit def syncInstance[F[_], O](
      implicit ev: ApplicativeError[F, Throwable]
  ): Sync[Pull[F, O, ?]] =
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
      def bracketCase[A, B](acquire: Pull[F, O, A])(
          use: A => Pull[F, O, B]
      )(release: (A, ExitCase[Throwable]) => Pull[F, O, Unit]): Pull[F, O, B] =
        new Pull(
          FreeC.bracketCase(acquire.free, (a: A) => use(a).free, (a: A, c) => release(a, c).free)
        )
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
    new Monad[Pull[F, O, ?]] {
      override def pure[A](a: A): Pull[F, O, A] = Pull.pure(a)
      override def flatMap[A, B](p: Pull[F, O, A])(f: A => Pull[F, O, B]): Pull[F, O, B] =
        p.flatMap(f)
      override def tailRecM[A, B](a: A)(f: A => Pull[F, O, Either[A, B]]): Pull[F, O, B] =
        f(a).flatMap {
          case Left(a)  => tailRecM(a)(f)
          case Right(b) => Pull.pure(b)
        }
    }
}
