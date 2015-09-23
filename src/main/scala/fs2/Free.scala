package fs2

import fs2.util.UF1._

abstract class Coyo[+F[_],+A] { self =>
  type R
  def effect: F[R]
  def post: Either[Throwable,R] => A
  def map[B](g: A => B) = new Coyo[F,B] {
    type R = self.R
    def effect = self.effect
    def post = self.post andThen g
  }
  def run[G[_],R](h: H[G,A,R])(implicit S: Sub1[F,G]) =
    h.f(S(effect), post)
  trait H[-F[_],-A,+R] { def f[x]: (F[x], Either[Throwable,x] => A) => R }
}

object Coyo {
  def eval[F[_],A](f: F[A]): Coyo[F,Either[Throwable,A]] = new Coyo[F,Either[Throwable,A]] {
    type R = A
    def effect = f
    def post = identity
  }
}

sealed trait Free[+F[_],+A] {
  import Free._
  def flatMap[F2[x]>:F[x],B](f: A => Free[F2,B]): Free[F2,B] = Bind(this, f)
  def map[B](f: A => B): Free[F,B] = Bind(this, f andThen (Free.Pure(_)))

  def runTranslate[G[_],A2>:A](g: F ~> G)(implicit G: Catchable[G]): G[A2] =
    step._runTranslate(g)

  protected def _runTranslate[G[_],A2>:A](g: F ~> G)(implicit G: Catchable[G]): G[A2]

  private[fs2] def inspect[G[_],R](
    fail: Throwable => R,
    pure: A => R,
    eval: Coyo[F,A] => R,
    bind: H[G,A,R])(implicit S: Sub1[F,G]): R

  trait H[G[_],-A,+R] { def f[x]: (Free[G,x], x => Free[G,A]) => R }

  def run[F2[x]>:F[x], A2>:A](implicit F2: Catchable[F2]): F2[A2] =
    (this: Free[F2,A2]).runTranslate(id)

  @annotation.tailrec
  private[fs2] final def step: Free[F,A] = this match {
    case Bind(Bind(x, f), g) => (x flatMap (a => f(a) flatMap g)).step
    case _ => this
  }
}

object Free {

  def attemptEval[F[_],A](a: F[A]): Free[F,Either[Throwable,A]] = Eval(a)
  def fail(err: Throwable): Free[Nothing,Nothing] = Fail(err)
  def pure[A](a: A): Free[Nothing,A] = Pure(a)
  def eval[F[_],A](a: F[A]): Free[F,A] = Eval(a) flatMap {
    case Left(e) => fail(e)
    case Right(a) => pure(a)
  }
  def suspend[F[_],A](fa: => Free[F,A]): Free[F,A] =
    pure(()) flatMap { _ => fa }

  private[fs2] case class Fail(err: Throwable) extends Free[Nothing,Nothing] {
    def _runTranslate[G[_],A2>:Nothing](g: Nothing ~> G)(implicit G: Catchable[G]): G[A2] =
      G.fail(err)
    def inspect[G[_],R](
      fail: Throwable => R,
      pure: Nothing => R,
      eval: Coyo[Nothing,Nothing] => R,
      bind: H[G,Nothing,R])(implicit S: Sub1[Nothing,G]): R
      = fail(err)
  }
  private[fs2] case class Pure[A](a: A) extends Free[Nothing,A] {
    def _runTranslate[G[_],A2>:A](g: Nothing ~> G)(implicit G: Catchable[G]): G[A2] =
      G.pure(a)
    def inspect[G[_],R](
      fail: Throwable => R,
      pure: A => R,
      eval: Coyo[Nothing,A] => R,
      bind: H[G,A,R])(implicit S: Sub1[Nothing,G]): R
      = pure(a)
  }
  private[fs2] case class Eval[+F[_],A](fa: F[A]) extends Free[F,Either[Throwable,A]] {
    def _runTranslate[G[_],A2>:Either[Throwable,A]](g: F ~> G)(implicit G: Catchable[G]): G[A2] =
      G.attempt { g(fa) }.asInstanceOf[G[A2]]

    def inspect[G[_],R](
      fail: Throwable => R,
      pure: Either[Throwable,A] => R,
      eval: Coyo[F,Either[Throwable,A]] => R,
      bind: H[G,Either[Throwable,A],R])(implicit S: Sub1[F,G]): R = eval(Coyo.eval(fa))
  }
  private[fs2] case class Bind[+F[_],R,A](r: Free[F,R], f: R => Free[F,A]) extends Free[F,A] {
    def _runTranslate[G[_],A2>:A](g: F ~> G)(implicit G: Catchable[G]): G[A2] =
      G.bind(r.runTranslate(g))(f andThen (_.runTranslate(g)))

    def inspect[G[_],R2](
      fail: Throwable => R2,
      pure: A => R2,
      eval: Coyo[F,A] => R2,
      bind: H[G,A,R2])(implicit S: Sub1[F,G]): R2
      = bind.f[R](Sub1.substFree(r), (r: R) => Sub1.substFree(f(r)))
  }

  implicit def monad[F[_]]: Monad[({ type f[x] = Free[F,x]})#f] =
  new Monad[({ type f[x] = Free[F,x]})#f] {
    def pure[A](a: A) = Pure(a)
    def bind[A,B](a: Free[F,A])(f: A => Free[F,B]) = a flatMap f
  }
}
