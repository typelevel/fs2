package fs2

import fs2.util.{Free,RealSupertype,Sub1,~>}
import fs2.StreamCore.{Env,R,RF,Token}

case class Scope[+F[_],+O](get: Free[R[F]#f,O]) {
  def map[O2](f: O => O2): Scope[F,O2] = Scope(get map f)

  def flatMap[F2[x]>:F[x],O2](f: O => Scope[F2,O2]): Scope[F2,O2] =
    Scope(get flatMap[R[F2]#f,O2] (f andThen (_.get)))

  def translate[G[_]](f: F ~> G): Scope[G,O] = Scope { Free.suspend[R[G]#f,O] {
    get.translate[R[G]#f](new (R[F]#f ~> R[G]#f) {
      def apply[A](r: RF[F,A]) = r match {
        case RF.Eval(fa) => RF.Eval(f(fa))
        case RF.FinishAcquire(token, cleanup) => RF.FinishAcquire(token, cleanup.translate(f))
        case _ => r.asInstanceOf[RF[G,A]] // Eval and FinishAcquire are only ctors that bind `F`
      }
    })
  }}

  def bindEnv[F2[_]](env: Env[F2])(implicit S: Sub1[F,F2]): Free[F2,O] = Free.suspend {
    type FO[x] = Free[F2,x]
    val B = new Free.B[R[F]#f,FO,O] { def f[x] = r => r match {
      case Right((r,g)) => g(r)
      case Left((i,g)) => i match {
        case StreamCore.RF.Eval(fx) => Free.attemptEval(S(fx)) flatMap g
        case StreamCore.RF.Interrupted => g(Right(env.interrupted()))
        case StreamCore.RF.Snapshot => g(Right(env.tracked.snapshot))
        case StreamCore.RF.NewSince(tokens) => g(Right(env.tracked.newSince(tokens)))
        case StreamCore.RF.Release(tokens) => env.tracked.release(tokens) match {
          // right way to handle this case - update the tokens map to run the finalizers
          // for this group when finishing the last acquire
          case None => sys.error("todo: release tokens while resources are being acquired")
          case Some(rs) =>
            rs.foldRight(Free.pure(()): Free[F2,Unit])((hd,tl) => hd flatMap { _ => tl })
              .flatMap { _ => g(Right(())) }
        }
        case StreamCore.RF.StartAcquire(token) =>
          env.tracked.startAcquire(token)
          g(Right(()))
        case StreamCore.RF.FinishAcquire(token, c) =>
          env.tracked.finishAcquire(token, Sub1.substFree(c))
          g(Right(()))
        case StreamCore.RF.CancelAcquire(token) =>
          env.tracked.cancelAcquire(token)
          g(Right(()))
      }
    }}
    get.fold[R[F]#f,FO,O](Free.pure, Free.fail, B)(Sub1.sub1[R[F]#f],implicitly[RealSupertype[O,O]])
  }
}

object Scope {
  def suspend[F[_],O](s: => Scope[F,O]): Scope[F,O] = pure(()) flatMap { _ => s }
  def pure[F[_],O](o: O): Scope[F,O] = Scope(Free.pure(o))
  def attemptEval[F[_],O](o: F[O]): Scope[F,Either[Throwable,O]] =
    Scope(Free.attemptEval[R[F]#f,O](StreamCore.RF.Eval(o)))
  def eval[F[_],O](o: F[O]): Scope[F,O] =
    attemptEval(o) flatMap { _.fold(fail, pure) }
  def fail[F[_],O](err: Throwable): Scope[F,O] =
    Scope(Free.fail(err))
  def interrupted[F[_]]: Scope[F,Boolean] =
    Scope(Free.eval[R[F]#f,Boolean](StreamCore.RF.Interrupted))
  def snapshot[F[_]]: Scope[F,Set[Token]] =
    Scope(Free.eval[R[F]#f,Set[Token]](StreamCore.RF.Snapshot))
  def newSince[F[_]](snapshot: Set[Token]): Scope[F,List[Token]] =
    Scope(Free.eval[R[F]#f,List[Token]](StreamCore.RF.NewSince(snapshot)))
  def release[F[_]](tokens: List[Token]): Scope[F,Unit] =
    Scope(Free.eval[R[F]#f,Unit](StreamCore.RF.Release(tokens)))
  def startAcquire[F[_]](token: Token): Scope[F,Unit] =
    Scope(Free.eval[R[F]#f,Unit](StreamCore.RF.StartAcquire(token)))
  def finishAcquire[F[_]](token: Token, cleanup: Free[F,Unit]): Scope[F,Unit] =
    Scope(Free.eval[R[F]#f,Unit](StreamCore.RF.FinishAcquire(token, cleanup)))
  def cancelAcquire[F[_]](token: Token): Scope[F,Unit] =
    Scope(Free.eval[R[F]#f,Unit](StreamCore.RF.CancelAcquire(token)))
}
