package streams

import process1.Process1

/**
 * Mixin trait for various non-primitive operations exposed on `Stream`
 * for syntactic convenience.
 */
private[streams]
trait StreamOps[+F[_],+A]
  /* extends Process1Ops[F,A] with TeeOps[F,A] with WyeOps[F,A] */ {
  self: Stream[F,A] =>

  // NB: methods in alphabetical order

  def ++[F2[x]>:F[x],B>:A](p2: Stream[F2,B])(implicit R: RealSupertype[A,B]): Stream[F2,B] =
    Stream.append(self: Stream[F2,B], p2)

  def append[F2[x]>:F[x],B>:A](p2: Stream[F2,B])(implicit R: RealSupertype[A,B]): Stream[F2,B] =
    Stream.append(self: Stream[F2,B], p2)

  def flatMap[F2[x]>:F[x],B](f: A => Stream[F2,B]): Stream[F2,B] =
    Stream.flatMap(self: Stream[F2,A])(f)

  def map[B](f: A => B): Stream[F,B] =
    Stream.map(self)(f)

  def onError[F2[x]>:F[x],B>:A](f: Throwable => Stream[F2,B])(implicit R: RealSupertype[A,B]): Stream[F2,B] =
    Stream.onError(self: Stream[F2,B])(f)

  def open: Pull[F, Nothing, Stream.Handle[F,A]] = Stream.open(self)

  def pipe[B](f: Process1[A,B]): Stream[F,B] = f(self)

  def pull[F2[x]>:F[x],A2>:A,B](
    using: Stream.Handle[F2,A2] => Pull[F2,B,Stream.Handle[F2,A2]])
    : Stream[F2,B] =
    Stream.pull(self: Stream[F2,A2])(using)

  def runFold[B](z: B)(f: (B,A) => B): Free[F,B] =
    Stream.runFold(self, z)(f)

  def runLog: Free[F,Vector[A]] =
    Stream.runFold(self, Vector.empty[A])(_ :+ _)
}
