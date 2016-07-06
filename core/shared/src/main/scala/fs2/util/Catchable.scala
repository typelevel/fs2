package fs2.util

trait Catchable[F[_]] extends Monad[F] {
  def fail[A](err: Throwable): F[A]
  def attempt[A](fa: F[A]): F[Either[Throwable,A]]
}

object Catchable {

  implicit val eitherThrowableInstance: Catchable[({type λ[a] = Either[Throwable,a]})#λ] = new Catchable[({type λ[a] = Either[Throwable,a]})#λ] {
    def pure[A](a: A): Either[Throwable,A] = Right(a)
    def flatMap[A, B](a: Either[Throwable,A])(f: A => Either[Throwable,B]): Either[Throwable,B] = a.right.flatMap(f)
    def attempt[A](a: Either[Throwable,A]): Either[Throwable,Either[Throwable, A]] = a match {
      case Right(a) => Right(Right(a))
      case Left(t) => Right(Left(t))
    }
    def fail[A](t: Throwable): Either[Throwable,A] = Left(t)
    override def toString = "Catchable[Either[Throwable,?]]"
  }
}
