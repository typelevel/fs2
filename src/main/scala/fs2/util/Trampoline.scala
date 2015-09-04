package fs2.util

private[fs2] sealed trait Trampoline[A] {
  import Trampoline._

  def flatMap[B](f: A => Trampoline[B]): Trampoline[B] =
    FlatMap(this, f)
  def map[B](f: A => B): Trampoline[B] =
    flatMap(f andThen (Return(_)))

  def run: A = Trampoline.run(this)
}

object Trampoline {
  private case class Return[A](a: A) extends Trampoline[A]
  private case class Suspend[A](resume: () => A) extends Trampoline[A]
  private case class FlatMap[A,B](sub: Trampoline[A], k: A => Trampoline[B]) extends Trampoline[B]

  def delay[A](a: => A): Trampoline[A] = Suspend(() => a)
  def done[A](a: A): Trampoline[A] = Return(a)
  def suspend[A](a: => Trampoline[A]) =
    Suspend(() => ()).flatMap { _ => a }

  @annotation.tailrec
  def run[A](t: Trampoline[A]): A = t match {
    case Return(a) => a
    case Suspend(r) => r()
    case FlatMap(x, f) => x match {
      case Return(a) => run(f(a))
      case Suspend(r) => run(f(r()))
      case FlatMap(y, g) => run(y flatMap (a => g(a) flatMap f))
    }
  }
}

