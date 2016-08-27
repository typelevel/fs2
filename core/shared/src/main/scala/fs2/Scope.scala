package fs2

import fs2.util.{Attempt,Free,RealSupertype,Sub1,~>}
import fs2.StreamCore.{Env,Algebra,AlgebraF,Token}

final class Scope[+F[_],+O] private (private val get: Free[AlgebraF[F]#f,O]) {

  def as[O2](o2: O2): Scope[F,O2] = map(_ => o2)

  def map[O2](f: O => O2): Scope[F,O2] = new Scope(get map f)

  def flatMap[F2[x]>:F[x],O2](f: O => Scope[F2,O2]): Scope[F2,O2] =
    new Scope(get flatMap[AlgebraF[F2]#f,O2] (f andThen (_.get)))

  def translate[G[_]](f: F ~> G): Scope[G,O] = new Scope(Free.suspend[AlgebraF[G]#f,O] {
    get.translate[AlgebraF[G]#f](new (AlgebraF[F]#f ~> AlgebraF[G]#f) {
      def apply[A](r: Algebra[F,A]) = r match {
        case Algebra.Eval(fa) => Algebra.Eval(f(fa))
        case Algebra.FinishAcquire(token, cleanup) => Algebra.FinishAcquire(token, cleanup.translate(f))
        case _ => r.asInstanceOf[Algebra[G,A]] // Eval and FinishAcquire are only ctors that flatMap `F`
      }
    })
  })

  def attempt: Scope[F,Attempt[O]] = new Scope(get.attempt)

  def bindEnv[F2[_]](env: Env[F2])(implicit S: Sub1[F,F2]): Free[F2,(List[Token],O)] = Free.suspend {
    type FO[x] = Free[F2,(List[Token],x)]
    get.fold[AlgebraF[F]#f,FO,O](new Free.Fold[AlgebraF[F]#f,FO,O] {
      def suspend(g: => FO[O]) = Free.suspend(g)
      def done(r: O) = Free.pure((Nil,r))
      def fail(t: Throwable) = Free.fail(t)
      def eval[X](i: AlgebraF[F]#f[X])(g: Attempt[X] => FO[O]) = i match {
        case Algebra.Eval(fx) => Free.attemptEval(S(fx)) flatMap g
        case Algebra.Interrupted => g(Right(env.interrupted()))
        case Algebra.Snapshot => g(Right(env.tracked.snapshot))
        case Algebra.NewSince(tokens) => g(Right(env.tracked.newSince(tokens)))
        case Algebra.Release(tokens) => env.tracked.release(tokens) match {
          case None =>
            g(Left(new RuntimeException("attempting to release resources still in process of being acquired")))
          case Some((rs,leftovers)) =>
            if (leftovers.isEmpty) StreamCore.runCleanup(rs) flatMap { e => g(Right(e)) }
            else StreamCore.runCleanup(rs) flatMap { e => g(Right(e)) map { case (ts,o) => (leftovers ++ ts, o) } }
        }
        case Algebra.StartAcquire(token) =>
          g(Right(env.tracked.startAcquire(token)))
        case Algebra.FinishAcquire(token, c) =>
          g(Right(env.tracked.finishAcquire(token, Sub1.substFree(c))))
        case Algebra.CancelAcquire(token) =>
          env.tracked.cancelAcquire(token)
          g(Right(()))
      }
      def bind[X](r: X)(g: X => FO[O]) = g(r)
    })(Sub1.sub1[AlgebraF[F]#f],implicitly[RealSupertype[O,O]])
  }

  override def toString = s"Scope($get)"
}

object Scope {
  def suspend[F[_],O](s: => Scope[F,O]): Scope[F,O] = pure(()) flatMap { _ => s }

  def pure[F[_],O](o: O): Scope[F,O] = new Scope(Free.pure(o))

  def attemptEval[F[_],O](o: F[O]): Scope[F,Attempt[O]] =
    new Scope(Free.attemptEval[AlgebraF[F]#f,O](Algebra.Eval(o)))

  def eval[F[_],O](o: F[O]): Scope[F,O] =
    attemptEval(o) flatMap { _.fold(fail, pure) }

  def evalFree[F[_],O](o: Free[F,O]): Scope[F,O] =
    new Scope(o.translate[AlgebraF[F]#f](new (F ~> AlgebraF[F]#f) { def apply[x](f: F[x]) = Algebra.Eval(f) }))

  def fail[F[_],O](err: Throwable): Scope[F,O] =
    new Scope(Free.fail(err))

  def interrupted[F[_]]: Scope[F,Boolean] =
    new Scope(Free.eval[AlgebraF[F]#f,Boolean](Algebra.Interrupted))

  def snapshot[F[_]]: Scope[F,Set[Token]] =
    new Scope(Free.eval[AlgebraF[F]#f,Set[Token]](Algebra.Snapshot))

  def newSince[F[_]](snapshot: Set[Token]): Scope[F,List[Token]] =
    new Scope(Free.eval[AlgebraF[F]#f,List[Token]](Algebra.NewSince(snapshot)))

  def release[F[_]](tokens: List[Token]): Scope[F,Attempt[Unit]] =
    new Scope(Free.eval[AlgebraF[F]#f,Attempt[Unit]](Algebra.Release(tokens)))

  def startAcquire[F[_]](token: Token): Scope[F,Boolean] =
    new Scope(Free.eval[AlgebraF[F]#f,Boolean](Algebra.StartAcquire(token)))

  def finishAcquire[F[_]](token: Token, cleanup: Free[F,Attempt[Unit]]): Scope[F,Boolean] =
    new Scope(Free.eval[AlgebraF[F]#f,Boolean](Algebra.FinishAcquire(token, cleanup)))

  def acquire[F[_]](token: Token, cleanup: Free[F,Attempt[Unit]]): Scope[F,Attempt[Unit]] =
    startAcquire(token) flatMap { ok =>
      if (ok) finishAcquire(token, cleanup).flatMap { ok =>
        if (ok) Scope.pure(Right(())) else evalFree(cleanup)
      }
      else evalFree(cleanup)
    }

  def cancelAcquire[F[_]](token: Token): Scope[F,Unit] =
    new Scope(Free.eval[AlgebraF[F]#f,Unit](Algebra.CancelAcquire(token)))

  def traverse[F[_],A,B](l: List[A])(f: A => Scope[F,B]): Scope[F,List[B]] =
    l.foldRight(Scope.pure[F,List[B]](List()))((hd,tl) => f(hd) flatMap { b => tl map (b :: _) })
}
