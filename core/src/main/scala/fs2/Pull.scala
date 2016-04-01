package fs2

import Pull._
import StreamCore.Token
import fs2.util.{Free,RealSupertype,Sub1}

class Pull[+F[_],+O,+R](private[fs2] val get: Free[P[F,O]#f,Option[Either[Throwable,R]]]) extends PullOps[F,O,R] {

  private
  def run_(asStep: Boolean): Stream[F,O] = Stream.mk { val s = {
    type G[x] = StreamCore[F,O]; type Out = Option[Either[Throwable,R]]
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
        case Right((r,g)) => StreamCore.Try(g(r))
      }}
    )(Sub1.sub1[P[F,O]#f], implicitly[RealSupertype[Out,Out]])
  }; if (asStep) s else StreamCore.scope(s) }


  def run: Stream[F,O] = run_(false)

  /** Run this `Pull`, but don't cleanup any resources acquired. */
  private[fs2]
  def runAsStep: Stream[F,O] = run_(true)
}

object Pull extends Pulls[Pull] with PullDerived with pull1 {
  type Stream[+F[_],+W] = fs2.Stream[F,W]

  trait P[F[_],O] { type f[x] = PF[F,O,x] }

  sealed trait PF[+F[_],+O,+R]
  object PF {
    case class Eval[F[_],O,R](f: Scope[F,R]) extends PF[F,O,R]
    case class Output[F[_],O](s: StreamCore[F,O]) extends PF[F,O,Unit]
  }

  def attemptEval[F[_],R](f: F[R]): Pull[F,Nothing,Either[Throwable,R]] =
    new Pull(Free.attemptEval[P[F,Nothing]#f,R](PF.Eval(Scope.eval(f))).map(e => Some(Right(e))))

  def done: Pull[Nothing,Nothing,Nothing] =
    new Pull(Free.pure(None))

  def eval[F[_],R](f: F[R]): Pull[F,Nothing,R] =
    attemptEval(f) flatMap { _.fold(fail, pure) }

  def evalScope[F[_],R](f: Scope[F,R]): Pull[F,Nothing,R] =
    new Pull(Free.eval[P[F,Nothing]#f,R](PF.Eval(f)).map(e => Some(Right(e))))

  def fail(err: Throwable): Pull[Nothing,Nothing,Nothing] =
    new Pull(Free.pure(Some(Left(err))))

  def flatMap[F[_],O,R0,R](p: Pull[F,O,R0])(f: R0 => Pull[F,O,R]): Pull[F,O,R] = new Pull(
    p.get.flatMap[P[F,O]#f,Option[Either[Throwable,R]]] {
      case Some(Right(r)) => Try(f(r)).get
      case None => Free.pure(None)
      case Some(Left(err)) => Free.pure(Some(Left(err)))
    }
  )

  def onError[F[_],O,R](p: Pull[F,O,R])(handle: Throwable => Pull[F,O,R]): Pull[F,O,R] =
    new Pull(
      p.get.flatMap[P[F,O]#f,Option[Either[Throwable,R]]] {
        case Some(Right(r)) => Free.pure(Some(Right(r)))
        case None => Free.pure(None)
        case Some(Left(err)) => Try(handle(err)).get
      }
    )

  def or[F[_],O,R](p1: Pull[F,O,R], p2: => Pull[F,O,R]): Pull[F,O,R] = new Pull (
    p1.get.flatMap[P[F,O]#f,Option[Either[Throwable,R]]] {
      case Some(Right(r)) => Free.pure(Some(Right(r)))
      case None => Try(p2).get
      case Some(Left(err)) => Free.pure(Some(Left(err)))
    }
  )

  def outputs[F[_],O](s: Stream[F,O]): Pull[F,O,Unit] =
    new Pull(Free.eval[P[F,O]#f,Unit](PF.Output(s.get)).map(_ => Some(Right(()))))

  private[fs2]
  def release(ts: List[Token]): Pull[Nothing,Nothing,Unit] =
    outputs(Stream.mk(StreamCore.release(ts).drain))

  def pure[R](r: R): Pull[Nothing,Nothing,R] =
    new Pull(Free.pure(Some(Right(r))))

  def run[F[_], O, R](p: Pull[F,O,R]): Stream[F,O] = p.run

  private
  def Try[F[_],O,R](p: => Pull[F,O,R]): Pull[F,O,R] =
    try p
    catch { case e: Throwable => fail(e) }
}

