package streams

import streams.util.UF1._

sealed trait Free[+F[_],+A] {
  import Free._
  def flatMap[F2[x]>:F[x],B](f: A => Free[F2,B]): Free[F2,B] = Bind(this, f)
  def map[B](f: A => B): Free[F,B] = Bind(this, f andThen (Free.Pure(_)))

  def run[G[_],A2>:A](g: F ~> G)(implicit G: Monad[G]): G[A2] = this match {
    case Pure(a) => G.pure(a)
    case Eval(fa) => g(fa.asInstanceOf[F[A2]])
    case Bind(fr, f) => G.bind(fr.run(g))(f andThen (_.run(g)))
  }
}
object Free {

  def eval[F[_],A](a: F[A]): Free[F,A] = Eval(a)

  def pure[A](a: A): Free[Nothing,A] = Pure(a)

  private[streams] case class Pure[A](a: A) extends Free[Nothing,A]
  private[streams] case class Eval[+F[_],A](fa: F[A]) extends Free[F,A]
  private[streams] case class Bind[+F[_],R,A](r: Free[F,R], f: R => Free[F,A]) extends Free[F,A]

  implicit def monad[F[_]]: Monad[({ type f[x] = Free[F,x]})#f] =
  new Monad[({ type f[x] = Free[F,x]})#f] {
    def pure[A](a: A) = Pure(a)
    def bind[A,B](a: Free[F,A])(f: A => Free[F,B]) = a flatMap f
  }
}
