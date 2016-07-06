package fs2.util

import scala.util.{ Try, Success, Failure }

trait Catchable[F[_]] extends Monad[F] {
  def fail[A](err: Throwable): F[A]
  def attempt[A](fa: F[A]): F[Either[Throwable,A]]
}

object Catchable {

  implicit val tryInstance: Catchable[Try] = new Catchable[Try] {
    def pure[A](a: A): Try[A] = Success(a)
    def flatMap[A, B](a: Try[A])(f: A => Try[B]): Try[B] = a.flatMap(f)
    def attempt[A](a: Try[A]): Try[Either[Throwable, A]] = a match {
      case Success(a) => Success(Right(a))
      case Failure(t) => Success(Left(t))
    }
    def fail[A](t: Throwable): Try[A] = Failure(t)
    override def toString = "Catchable[Try]"
  }
}
