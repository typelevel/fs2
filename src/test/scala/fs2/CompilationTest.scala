package fs2

import fs2.util.Task

object ThisModuleShouldCompile {

  /* Some checks that `.pull` can be used without annotations */
  val a = Stream(1,2,3,4) pull process1.take(2)
  val a2 = Stream.eval(Task.now(1)) pull process1.take(2)
  val a3 = Stream(1,2,3,4) pull[Int] process1.take(2)

  /* Also in a polymorphic context. */
  def a4[F[_],A](s: Stream[F,A]) = s pull process1.take(2)
  def a5[F[_],A](s: Stream[F,A]): Stream[F,A] = s pull process1.take(2)
  def a6[F[_],A](s: Stream[F,A]): Stream[F,A] = s pullv process1.take[F,A](2)

  val b = process1.take(2)
  val c = Stream(1,2,3) ++ Stream(4,5,6)
  val d = Stream(1,2,3) ++ Stream.eval(Task.now(4))
}

