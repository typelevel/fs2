package fs2

import fs2.util.Task

object ThisModuleShouldCompile {

  /* Some checks that `.pull` can be used without annotations */
  val a = Stream(1,2,3,4) pull process1.take(2)
  val aa: Stream[Nothing,Int] = Stream(1,2,3,4) pull process1.take(2)
  val a2 = Stream.eval(Task.now(1)) pull process1.take(2)
  val a3 = Stream(1,2,3,4) pull[Int] process1.take(2)

  /* Also in a polymorphic context. */
  def a4[F[_],A](s: Stream[F,A]) = s pull process1.take(2)
  def a5[F[_],A](s: Stream[F,A]): Stream[F,A] = s pull process1.take(2)
  def a6[F[_],A](s: Stream[F,A]): Stream[F,A] = s pullv process1.take[F,A](2)

  val b = process1.take(2)
  val c = Stream(1,2,3) ++ Stream(4,5,6)
  val d = Stream(1,2,3) ++ Stream.eval(Task.now(4))
  val e = Stream(1,2,3).flatMap(i => Stream.eval(Task.now(i)))
  val f = (Stream(1,2,3).covary[Task]).pullv(h => h.await1 flatMap { case Step(hd,_) => Pull.output1(hd) })
  val g = Stream(1,2,3).pullv(h => h.await1 flatMap { case Step(hd,_) => Pull.output1(hd) })
  val h = Stream(1,2,3).pullv(h => h.await1 flatMap { case Step(hd,_) => Pull.eval(Task.now(1)) >> Pull.output1(hd) })

  /* Check that `Async[Task]` can be found in companion object without imports. */
  implicit val S = Strategy.sequential
  val i = Stream.eval(Task.now(1)).pull { h => h.invAwaitAsync }
}

