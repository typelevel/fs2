package fs2

import Pull._
import StreamCore.Token
import fs2.util.{Attempt,Free,NonFatal,RealSupertype,Sub1}

/**
 * A pull allows acquiring elements from a stream in a resource safe way,
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
class Pull[+F[_],+O,+R](private[fs2] val get: Free[P[F,O]#f,Option[Attempt[R]]]) extends PullOps[F,O,R] {

  private
  def close_(asStep: Boolean): Stream[F,O] = Stream.mk { val s = {
    type G[x] = StreamCore[F,O]; type Out = Option[Attempt[R]]
    get.fold[P[F,O]#f,G,Out](
      StreamCore.suspend,
      o => o match {
        case None => StreamCore.empty
        case Some(e) => e.fold(StreamCore.fail(_), _ => StreamCore.empty)
      },
      err => StreamCore.fail(err),
      new Free.B[P[F,O]#f,G,Out] { def f[x] = r => r match {
        case Left((PF.Eval(fr), g)) => StreamCore.evalScope(fr.attempt) flatMap g
        case Left((PF.Output(o), g)) => StreamCore.append(o, StreamCore.suspend(g(Right(()))))
        case Right((r,g)) => StreamCore.attemptStream(g(r))
      }}
    )(Sub1.sub1[P[F,O]#f], implicitly[RealSupertype[Out,Out]])
  }; if (asStep) s else StreamCore.scope(s) }


  def close: Stream[F,O] = close_(false)

  /** Close this `Pull`, but don't cleanup any resources acquired. */
  private[fs2]
  def closeAsStep: Stream[F,O] = close_(true)
}

object Pull extends PullDerived with pull1 {
  type Stream[+F[_],+W] = fs2.Stream[F,W]

  trait P[F[_],O] { type f[x] = PF[F,O,x] }

  sealed trait PF[+F[_],+O,+R]
  object PF {
    case class Eval[F[_],O,R](f: Scope[F,R]) extends PF[F,O,R]
    case class Output[F[_],O](s: StreamCore[F,O]) extends PF[F,O,Unit]
  }

  def attemptEval[F[_],R](f: F[R]): Pull[F,Nothing,Attempt[R]] =
    new Pull(Free.attemptEval[P[F,Nothing]#f,R](PF.Eval(Scope.eval(f))).map(e => Some(Right(e))))

  /** The completed `Pull`. Reads and outputs nothing. */
  def done: Pull[Nothing,Nothing,Nothing] =
    new Pull(Free.pure(None))

  /** Promote an effect to a `Pull`. */
  def eval[F[_],R](f: F[R]): Pull[F,Nothing,R] =
    attemptEval(f) flatMap { _.fold(fail, pure) }

  def evalScope[F[_],R](f: Scope[F,R]): Pull[F,Nothing,R] =
    new Pull(Free.eval[P[F,Nothing]#f,R](PF.Eval(f)).map(e => Some(Right(e))))

  /** The `Pull` that reads and outputs nothing, and fails with the given error. */
  def fail(err: Throwable): Pull[Nothing,Nothing,Nothing] =
    new Pull(Free.pure(Some(Left(err))))

  def flatMap[F[_],O,R0,R](p: Pull[F,O,R0])(f: R0 => Pull[F,O,R]): Pull[F,O,R] = new Pull(
    p.get.flatMap[P[F,O]#f,Option[Attempt[R]]] {
      case Some(Right(r)) => attemptPull(f(r)).get
      case None => Free.pure(None)
      case Some(Left(err)) => Free.pure(Some(Left(err)))
    }
  )

  /** If `p` terminates with `fail(e)`, invoke `handle(e)`. */
  def onError[F[_],O,R](p: Pull[F,O,R])(handle: Throwable => Pull[F,O,R]): Pull[F,O,R] =
    new Pull(
      p.get.flatMap[P[F,O]#f,Option[Attempt[R]]] {
        case Some(Right(r)) => Free.pure(Some(Right(r)))
        case None => Free.pure(None)
        case Some(Left(err)) => attemptPull(handle(err)).get
      }
    )

  /**
   * Consult `p2` if `p` fails due to an `await` on an exhausted `Handle`.
   * If `p` fails due to an error, `p2` is not consulted.
   */
  def or[F[_],O,R](p1: Pull[F,O,R], p2: => Pull[F,O,R]): Pull[F,O,R] = new Pull (
    p1.get.flatMap[P[F,O]#f,Option[Attempt[R]]] {
      case Some(Right(r)) => Free.pure(Some(Right(r)))
      case None => attemptPull(p2).get
      case Some(Left(err)) => Free.pure(Some(Left(err)))
    }
  )

  /** Write a stream to the output of this `Pull`. */
  def outputs[F[_],O](s: Stream[F,O]): Pull[F,O,Unit] =
    new Pull(Free.eval[P[F,O]#f,Unit](PF.Output(s.get)).map(_ => Some(Right(()))))

  private[fs2]
  def release(ts: List[Token]): Pull[Nothing,Nothing,Unit] =
    outputs(Stream.mk(StreamCore.release(ts).drain))

  /** The `Pull` that reads and outputs nothing, and succeeds with the given value, `R`. */
  def pure[R](r: R): Pull[Nothing,Nothing,R] =
    new Pull(Free.pure(Some(Right(r))))

  /** Interpret this `Pull` to produce a `Stream`. The result type `R` is discarded. */
  def close[F[_], O, R](p: Pull[F,O,R]): Stream[F,O] = p.close

  private def attemptPull[F[_],O,R](p: => Pull[F,O,R]): Pull[F,O,R] =
    try p catch { case NonFatal(e) => fail(e) }
}
