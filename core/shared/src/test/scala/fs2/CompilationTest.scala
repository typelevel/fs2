package fs2

import cats.effect.IO

object ThisModuleShouldCompile {

  /* Some checks that `.pull` can be used without annotations */
  val a = Stream.pure(1,2,3,4) through pipe.take(2)
  val a2 = Stream.eval(IO.pure(1)) through pipe.take(2)
  val a3 = Stream(1,2,3,4) through[Int] pipe.take(2)
  val a3a = Stream(1,2,3).covary[IO].pull.uncons1.close
  val a3b = Stream.eval(IO.pure(1)).pull.uncons1.close

  /* Also in a polymorphic context. */
  def a4[F[_],A](s: Stream[F,A]) = s through pipe.take(2)
  def a5[F[_],A](s: Stream[F,A]): Stream[F,A] = s through pipe.take(2)
  def a6[F[_],A](s: Stream[F,A]): Stream[F,A] = s through pipe.take(2)

  val b = pipe.take[Pure,Int](2)
  val c = Stream(1,2,3) ++ Stream(4,5,6)
  val d = Stream(1,2,3) ++ Stream.eval(IO.pure(4))
  val d1 = Stream(1,2,3).pure ++ Stream.eval(IO.pure(4))
  val d2 = Stream.eval(IO.pure(4)) ++ Stream(1,2,3)
  val d3 = Stream.eval(IO.pure(4)) ++ Stream(1,2,3).pure
  val d4 = Stream.eval(IO.pure(4)) ++ Stream(1,2,3).pure.covaryPure[IO]
  val d5 = Stream.eval(IO.pure(4)) ++ (Stream(1,2,3).pure: Stream[IO, Int])
  val e = Stream(1,2,3).flatMap(i => Stream.eval(IO.pure(i)))
  val f = (Stream(1,2,3).covary[IO]).pull.uncons1.flatMapOpt { case (hd,_) => Pull.output1(hd).as(None) }.close
  val g = Stream(1,2,3).pull.uncons1.flatMapOpt { case (hd,_) => Pull.output1(hd).as(None) }.close
  val h = Stream(1,2,3).pull.uncons1.flatMapOpt { case (hd,_) => Pull.eval(IO.pure(1)) >> Pull.output1(hd).as(None) }.close

  import scala.concurrent.ExecutionContext.Implicits.global
  val i = Stream.eval(IO.pure(1)).pull.unconsAsync.close

  val j: Pipe[IO,Int,Int] = pipe.take[Pure,Int](2)
  val k = pipe.take[Pure,Int](2).covary[IO]
  val l = pipe.take[Pure,Int](2).attachL(pipe2.interleave)
  val m = pipe.take[Pure,Int](2).attachR(pipe2.interleave)
  val n = pipe.take[Pure,Int](2).attachR(pipe2.interleave)
}
