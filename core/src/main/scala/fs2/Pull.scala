package fs2

import fs2.util.{Free,RealSupertype,Sub1}
import Pull._

class Pull[+F[_],+O,+R](private[fs2] val get: Scope[F,Free[P[F,O]#f,Option[Either[Throwable,R]]]]) extends PullOps[F,O,R] {

  def run: Stream[F,O] = Stream.mk { StreamCore.scope { StreamCore.evalScope { get map { f =>
    type G[x] = StreamCore[F,O]; type Out = Option[Either[Throwable,R]]
    f.fold[P[F,O]#f,G,Out](
      o => o match {
        case None => StreamCore.empty
        case Some(e) => e.fold(StreamCore.fail, _ => StreamCore.empty)
      },
      err => StreamCore.fail(err),
      new Free.B[P[F,O]#f,G,Out] { def f[x] = (r, g) => r match {
        case Left(PF.Eval(fr)) => StreamCore.eval(fr) flatMap g
        case Left(PF.Output(o)) => StreamCore.append(o, StreamCore.suspend(g(())))
        case Right(r) => StreamCore.Try(g(r))
      }}
    )(Sub1.sub1[P[F,O]#f], implicitly[RealSupertype[Out,Out]])
  }} flatMap (identity) }}

}

object Pull extends Pulls[Pull] with PullDerived with pull1 {
  type Stream[+F[_],+W] = fs2.Stream[F,W]

  trait P[F[_],O] { type f[x] = PF[F,O,x] }

  sealed trait PF[+F[_],+O,+R]
  object PF {
    case class Eval[F[_],O,R](f: F[R]) extends PF[F,O,R]
    case class Output[F[_],O](s: StreamCore[F,O]) extends PF[F,O,Unit]
  }

  def output[F[_],O](s: StreamCore[F,O]): Pull[F,O,Unit] =
    new Pull(Scope.pure(Free.eval[P[F,O]#f,Unit](PF.Output(s)).map(_ => Some(Right(())))))

  def pure[R](r: R): Pull[Nothing,Nothing,R] =
    new Pull(Scope.pure(Free.pure(Some(Right(r)))))

  def fail(err: Throwable): Pull[Nothing,Nothing,Nothing] =
    new Pull(Scope.pure(Free.pure(Some(Left(err)))))

  def done: Pull[Nothing,Nothing,Nothing] =
    new Pull(Scope.pure(Free.pure(None)))

  def flatMap[F[_],O,R0,R](p: Pull[F,O,R0])(f: R0 => Pull[F,O,R]): Pull[F,O,R] =
    ???

  def eval[F[_], R](f: F[R]): Pull[F,Nothing,R] = ???
  def onError[F[_], O, R](p: Pull[F,O,R])(handle: Throwable => Pull[F,O,R]): Pull[F,O,R] = ???
  def or[F[_], O, R](p1: Pull[F,O,R],p2: => Pull[F,O,R]): Pull[F,O,R] = ???
  def outputs[F[_], O](s: Pull.Stream[F,O]): Pull[F,O,Unit] = ???
  def run[F[_], O, R](p: Pull[F,O,R]): Pull.Stream[F,O] = ???
}

