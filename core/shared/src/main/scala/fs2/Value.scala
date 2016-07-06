package fs2

import fs2.util.Catchable

case class Value[+A](get: Either[Throwable, A]) {
  def value: A = get.fold(e => throw e, a => a)
}

object Value {
  implicit val catchableInstance: Catchable[Value] = new Catchable[Value] {
    def pure[A](a: A): Value[A] = Value(Right(a))
    def flatMap[A, B](a: Value[A])(f: A => Value[B]): Value[B] = a.get match {
      case Left(t) => Value(Left(t))
      case Right(a) => f(a)
    }
    def attempt[A](a: Value[A]): Value[Either[Throwable,A]] = Value(Right(a.get))
    def fail[A](t: Throwable): Value[A] = Value(Left(t))
    override def toString = "Catchable[Value]"
  }
}

