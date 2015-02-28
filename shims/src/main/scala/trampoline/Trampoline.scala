package trampoline

trait Trampoline[+A] {
  def map[B](f: A => B): Trampoline[B]
  def run[B >: A]: B
}

object Trampoline {
  def done[A](a: A): Trampoline[A] = ???
  def delay[A](a: => A): Trampoline[A] = ???
  def suspend[A](a: => Trampoline[A]): Trampoline[A] = ???
}
