package streams

import streams.util.UF1._

sealed trait Free[+F[_],+A] {
  import Free._
  def flatMap[F2[x]>:F[x],B](f: A => Free[F2,B]): Free[F2,B] = Bind(this, f)
  def map[B](f: A => B): Free[F,B] = Bind(this, f andThen (Free.Pure(_)))

  def runTranslate[G[_],A2>:A](g: F ~> G)(implicit G: Catchable[G]): G[A2] =
    step._runTranslate(g)

  protected def _runTranslate[G[_],A2>:A](g: F ~> G)(implicit G: Catchable[G]): G[A2]

  def run[F2[x]>:F[x], A2>:A](implicit F2: Catchable[F2]): F2[A2] =
    (this: Free[F2,A2]).runTranslate(id)

  @annotation.tailrec
  private[streams] final def step: Free[F,A] = this match {
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

  private[streams] case class Fail(err: Throwable) extends Free[Nothing,Nothing] {
    def _runTranslate[G[_],A2>:Nothing](g: Nothing ~> G)(implicit G: Catchable[G]): G[A2] =
      G.fail(err)
  }
  private[streams] case class Pure[A](a: A) extends Free[Nothing,A] {
    def _runTranslate[G[_],A2>:A](g: Nothing ~> G)(implicit G: Catchable[G]): G[A2] =
      G.pure(a)
  }
  private[streams] case class Eval[+F[_],A](fa: F[A]) extends Free[F,Either[Throwable,A]] {
    def _runTranslate[G[_],A2>:Either[Throwable,A]](g: F ~> G)(implicit G: Catchable[G]): G[A2] =
      G.attempt { g(fa) }.asInstanceOf[G[A2]]
  }
  private[streams] case class Bind[+F[_],R,A](r: Free[F,R], f: R => Free[F,A]) extends Free[F,A] {
    def _runTranslate[G[_],A2>:A](g: F ~> G)(implicit G: Catchable[G]): G[A2] =
      G.bind(r.runTranslate(g))(f andThen (_.runTranslate(g)))
  }


  implicit def monad[F[_]]: Monad[({ type f[x] = Free[F,x]})#f] =
  new Monad[({ type f[x] = Free[F,x]})#f] {
    def pure[A](a: A) = Pure(a)
    def bind[A,B](a: Free[F,A])(f: A => Free[F,B]) = a flatMap f
  }
}
