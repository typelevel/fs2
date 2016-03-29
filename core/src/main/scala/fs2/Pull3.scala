package fs2

import fs2.util.{Free,RealSupertype,Sub1}
import Pull3._

class Pull3[+F[_],+O,+R](val get: Scope[F,Free[P[F,O]#f,Option[Either[Throwable,R]]]]) {

  def run: Stream1[F,O] = Stream1 { StreamCore.scope { StreamCore.evalScope { get map { f =>
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

  def flatMap[F2[x]>:F[x],O2>:O,R2](f: R => Pull3[F2,O2,R2])(implicit S: Sub1[F,F2]): Pull3[F2,O2,R2] = ???
}

object Pull3 {

  trait P[F[_],O] { type f[x] = PF[F,O,x] }

  sealed trait PF[+F[_],+O,+R]
  object PF {
    case class Eval[F[_],O,R](f: F[R]) extends PF[F,O,R]
    case class Output[F[_],O](s: StreamCore[F,O]) extends PF[F,O,Unit]
  }

  def output[F[_],O](s: StreamCore[F,O]): Pull3[F,O,Unit] =
    new Pull3(Scope.pure(Free.eval[P[F,O]#f,Unit](PF.Output(s)).map(_ => Some(Right(())))))

  def pure[R](r: R): Pull3[Nothing,Nothing,R] =
    new Pull3(Scope.pure(Free.pure(Some(Right(r)))))

  def fail(err: Throwable): Pull3[Nothing,Nothing,Nothing] =
    new Pull3(Scope.pure(Free.pure(Some(Left(err)))))

  def done: Pull3[Nothing,Nothing,Nothing] =
    new Pull3(Scope.pure(Free.pure(None)))
}

