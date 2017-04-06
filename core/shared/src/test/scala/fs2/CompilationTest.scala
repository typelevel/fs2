package fs2

object ThisModuleShouldCompile {

  /* Some checks that `.pull` can be used without annotations */
  val a = Stream.pure(1,2,3,4) through pipe.take(2)
  val a2 = Stream.eval(Task.now(1)) through pipe.take(2)
  val a3 = Stream(1,2,3,4) through[Int] pipe.take(2)
  val a3a = Stream(1,2,3).covary[Task] pull { h => h.await1 }
  val a3b = Stream.eval(Task.now(1)) pull { h => h.await1 }

  /* Also in a polymorphic context. */
  def a4[F[_],A](s: Stream[F,A]) = s through pipe.take(2)
  def a5[F[_],A](s: Stream[F,A]): Stream[F,A] = s through pipe.take(2)
  def a6[F[_],A](s: Stream[F,A]): Stream[F,A] = s through pipe.take(2)

  val b = pipe.take[Pure,Int](2)
  val c = Stream(1,2,3) ++ Stream(4,5,6)
  val d = Stream(1,2,3) ++ Stream.eval(Task.now(4))
  val d1 = Stream(1,2,3).pure ++ Stream.eval(Task.now(4))
  val d2 = Stream.eval(Task.now(4)) ++ Stream(1,2,3)
  val d3 = Stream.eval(Task.now(4)) ++ Stream(1,2,3).pure
  val d4 = Stream.eval(Task.now(4)) ++ Stream(1,2,3).pure.covary[Task]
  val d5 = Stream.eval(Task.now(4)) ++ (Stream(1,2,3).pure: Stream[Task, Int])
  val e = Stream(1,2,3).flatMap(i => Stream.eval(Task.now(i)))
  val f = (Stream(1,2,3).covary[Task]).pull(h => h.await1 flatMap { case (hd,_) => Pull.output1(hd) })
  val g = Stream(1,2,3).pull(h => h.await1 flatMap { case (hd,_) => Pull.output1(hd) })
  val h = Stream(1,2,3).pull(h => h.await1 flatMap { case (hd,_) => Pull.eval(Task.now(1)) >> Pull.output1(hd) })

  /* Check that `Async[Task]` can be found in companion object without imports. */
  import scala.concurrent.ExecutionContext.Implicits.global
  val i = Stream.eval(Task.now(1)).pull { h => h.awaitAsync }

  val j: Pipe[Task,Int,Int] = pipe.take[Pure,Int](2)
  val k = pipe.take[Pure,Int](2).covary[Task]
  val l = pipe.take[Pure,Int](2).attachL(pipe2.interleave)
  val m = pipe.take[Pure,Int](2).attachR(pipe2.interleave)
  val n = pipe.take[Pure,Int](2).attachR(pipe2.interleave)
}
