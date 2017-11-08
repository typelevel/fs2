package fs2

import cats.effect.IO

object ThisModuleShouldCompile {

  /* Some checks that `.pull` can be used without annotations */
  Stream(1,2,3,4) through (_.take(2))
  Stream.eval(IO.pure(1)) through (_.take(2))
  Stream(1,2,3,4) through[Int] (_.take(2))
  Stream(1,2,3).covary[IO].pull.uncons1.stream
  Stream.eval(IO.pure(1)).pull.uncons1.stream

  /* Also in a polymorphic context. */
  def a[F[_],A](s: Stream[F,A]) = s through (_.take(2))
  def b[F[_],A](s: Stream[F,A]): Stream[F,A] = s through (_.take(2))
  def c[F[_],A](s: Stream[F,A]): Stream[F,A] = s through (_.take(2))

  Stream(1,2,3) ++ Stream(4,5,6)
  Stream(1,2,3) ++ Stream.eval(IO.pure(4))
  Stream(1,2,3) ++ Stream.eval(IO.pure(4))
  Stream.eval(IO.pure(4)) ++ Stream(1,2,3)
  Stream.eval(IO.pure(4)) ++ Stream(1,2,3).covary[IO]
  Stream.eval(IO.pure(4)) ++ (Stream(1,2,3): Stream[IO, Int])
  Stream(1,2,3).flatMap(i => Stream.eval(IO.pure(i)))
  (Stream(1,2,3).covary[IO]).pull.uncons1.flatMap {
    case Some((hd,_)) => Pull.output1(hd).as(None)
    case None => Pull.pure(None)
  }.stream
  Stream(1,2,3).pull.uncons1.flatMap {
    case Some((hd,_)) => Pull.output1(hd).as(None)
    case None => Pull.pure(None)
  }.stream
  Stream(1,2,3).pull.uncons1.flatMap {
    case Some((hd,_)) => Pull.eval(IO.pure(1)) *> Pull.output1(hd).as(None)
    case None => Pull.pure(None)
  }.stream
  (Stream(1,2,3).evalMap(IO(_))): Stream[IO,Int]
  (Stream(1,2,3).flatMap(i => Stream.eval(IO(i)))): Stream[IO,Int]

  val s: Stream[IO,Int] = if (true) Stream(1,2,3) else Stream.eval(IO(10))

  import scala.concurrent.ExecutionContext.Implicits.global
  Stream.eval(IO.pure(1)).pull.unconsAsync.stream

  val t2p: Pipe[Pure,Int,Int] = _.take(2)
  val t2: Pipe[IO,Int,Int] = _.take(2)
  t2p.covary[IO]
  val p2: Pipe2[IO,Int,Int,Int] = (s1,s2) => s1.interleave(s2)
  t2.attachL(p2)
  t2.attachR(p2)

  val p: Pull[Pure,Nothing,Option[(Segment[Int,Unit],Stream[Pure,Int])]] = Stream(1, 2, 3).pull.uncons
  val q: Pull[IO,Nothing,Option[(Segment[Int,Unit],Stream[Pure,Int])]] = p

  // With cats implicits enabled, some of the above fail to compile due to the cats syntax being invariant:
  {
    import cats.implicits._
    Stream(1,2,3).covary[IO].flatMap(i => Stream.eval(IO.pure(i)))
    (Stream(1,2,3).covary[IO].flatMap(i => Stream.eval(IO(i)))): Stream[IO,Int]
  }
}
