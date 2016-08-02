package fs2

import fs2.util.{Attempt,Free,RealSupertype,Sub1,~>}
import fs2.StreamCore.{Env,R,RF,Token}

final class Scope[+F[_],+O] private (private val get: Free[R[F]#f,O]) {

  def as[O2](o2: O2): Scope[F,O2] = map(_ => o2)

  def map[O2](f: O => O2): Scope[F,O2] = new Scope(get map f)

  def flatMap[F2[x]>:F[x],O2](f: O => Scope[F2,O2]): Scope[F2,O2] =
    new Scope(get flatMap[R[F2]#f,O2] (f andThen (_.get)))

  def translate[G[_]](f: F ~> G): Scope[G,O] = new Scope(Free.suspend[R[G]#f,O] {
    get.translate[R[G]#f](new (R[F]#f ~> R[G]#f) {
      def apply[A](r: RF[F,A]) = r match {
        case RF.Eval(fa) => RF.Eval(f(fa))
        case RF.FinishAcquire(token, cleanup) => RF.FinishAcquire(token, cleanup.translate(f))
        case _ => r.asInstanceOf[RF[G,A]] // Eval and FinishAcquire are only ctors that flatMap `F`
      }
    })
  })

  def attempt: Scope[F,Attempt[O]] = new Scope(get.attempt)

  def bindEnv[F2[_]](env: Env[F2])(implicit S: Sub1[F,F2]): Free[F2,(List[Token],O)] = Free.suspend {
    type FO[x] = Free[F2,(List[Token],x)]
    val B = new Free.B[R[F]#f,FO,O] { def f[x] = r => r match {
      case Right((r,g)) => g(r)
      case Left((i,g)) => i match {
        case StreamCore.RF.Eval(fx) => Free.attemptEval(S(fx)) flatMap g
        case StreamCore.RF.Interrupted => g(Right(env.interrupted()))
        case StreamCore.RF.Snapshot => g(Right(env.tracked.snapshot))
        case StreamCore.RF.NewSince(tokens) => g(Right(env.tracked.newSince(tokens)))
        case StreamCore.RF.Release(tokens) => env.tracked.release(tokens) match {
          case None =>
            g(Left(new RuntimeException("attempting to release resources still in process of being acquired")))
          case Some((rs,leftovers)) =>
            if (leftovers.isEmpty) StreamCore.runCleanup(rs) flatMap { e => g(Right(e)) }
            else StreamCore.runCleanup(rs) flatMap { e => g(Right(e)) map { case (ts,o) => (leftovers ++ ts, o) } }
        }
        case StreamCore.RF.StartAcquire(token) =>
          g(Right(env.tracked.startAcquire(token)))
        case StreamCore.RF.FinishAcquire(token, c) =>
          g(Right(env.tracked.finishAcquire(token, Sub1.substFree(c))))
        case StreamCore.RF.CancelAcquire(token) =>
          env.tracked.cancelAcquire(token)
          g(Right(()))
      }
    }}
    get.fold[R[F]#f,FO,O](
      Free.suspend,
      r => Free.pure((Nil,r)),
      Free.fail,
      B)(Sub1.sub1[R[F]#f],implicitly[RealSupertype[O,O]])
  }

  override def toString = s"Scope($get)"
}

object Scope {
  def suspend[F[_],O](s: => Scope[F,O]): Scope[F,O] = pure(()) flatMap { _ => s }

  def pure[F[_],O](o: O): Scope[F,O] = new Scope(Free.pure(o))

  def attemptEval[F[_],O](o: F[O]): Scope[F,Attempt[O]] =
    new Scope(Free.attemptEval[R[F]#f,O](StreamCore.RF.Eval(o)))

  def eval[F[_],O](o: F[O]): Scope[F,O] =
    attemptEval(o) flatMap { _.fold(fail, pure) }

  def evalFree[F[_],O](o: Free[F,O]): Scope[F,O] =
    new Scope(o.translate[R[F]#f](new (F ~> R[F]#f) { def apply[x](f: F[x]) = StreamCore.RF.Eval(f) }))

  def fail[F[_],O](err: Throwable): Scope[F,O] =
    new Scope(Free.fail(err))

  def interrupted[F[_]]: Scope[F,Boolean] =
    new Scope(Free.eval[R[F]#f,Boolean](StreamCore.RF.Interrupted))

  def snapshot[F[_]]: Scope[F,Set[Token]] =
    new Scope(Free.eval[R[F]#f,Set[Token]](StreamCore.RF.Snapshot))

  def newSince[F[_]](snapshot: Set[Token]): Scope[F,List[Token]] =
    new Scope(Free.eval[R[F]#f,List[Token]](StreamCore.RF.NewSince(snapshot)))

  def release[F[_]](tokens: List[Token]): Scope[F,Attempt[Unit]] =
    new Scope(Free.eval[R[F]#f,Attempt[Unit]](StreamCore.RF.Release(tokens)))

  def startAcquire[F[_]](token: Token): Scope[F,Boolean] =
    new Scope(Free.eval[R[F]#f,Boolean](StreamCore.RF.StartAcquire(token)))

  def finishAcquire[F[_]](token: Token, cleanup: Free[F,Attempt[Unit]]): Scope[F,Boolean] =
    new Scope(Free.eval[R[F]#f,Boolean](StreamCore.RF.FinishAcquire(token, cleanup)))

  def acquire[F[_]](token: Token, cleanup: Free[F,Attempt[Unit]]): Scope[F,Attempt[Unit]] =
    startAcquire(token) flatMap { ok =>
      if (ok) finishAcquire(token, cleanup).flatMap { ok =>
        if (ok) Scope.pure(Right(())) else evalFree(cleanup)
      }
      else evalFree(cleanup)
    }

  def cancelAcquire[F[_]](token: Token): Scope[F,Unit] =
    new Scope(Free.eval[R[F]#f,Unit](StreamCore.RF.CancelAcquire(token)))

  def traverse[F[_],A,B](l: List[A])(f: A => Scope[F,B]): Scope[F,List[B]] =
    l.foldRight(Scope.pure[F,List[B]](List()))((hd,tl) => f(hd) flatMap { b => tl map (b :: _) })
}
