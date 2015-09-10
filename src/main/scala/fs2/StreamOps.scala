package fs2

import process1.Process1

/**
 * Mixin trait for various non-primitive operations exposed on `Stream`
 * for syntactic convenience.
 */
private[fs2]
trait StreamOps[+F[_],+A]
  /* extends Process1Ops[F,A] with TeeOps[F,A] with WyeOps[F,A] */ {
  self: Stream[F,A] =>

  import Stream.Handle

  // NB: methods in alphabetical order

  def ++[F2[x]>:F[x],B>:A](p2: => Stream[F2,B])(implicit R: RealSupertype[A,B]): Stream[F2,B] =
    Stream.append(self: Stream[F2,B], p2)

  def append[F2[x]>:F[x],B>:A](p2: => Stream[F2,B])(implicit R: RealSupertype[A,B]): Stream[F2,B] =
    Stream.append(self: Stream[F2,B], p2)

  def flatMap[F2[x]>:F[x],B](f: A => Stream[F2,B]): Stream[F2,B] =
    Stream.flatMap(self: Stream[F2,A])(f)

  def map[B](f: A => B): Stream[F,B] =
    Stream.map(self)(f)

  def onError[F2[x]>:F[x],B>:A](f: Throwable => Stream[F2,B])(implicit R: RealSupertype[A,B]): Stream[F2,B] =
    Stream.onError(self: Stream[F2,B])(f)

  def open: Pull[F, Nothing, Handle[F,A]] = Stream.open(self)

  def pipe[B](f: Process1[A,B]): Stream[F,B] = f(self)

  def pull[F2[x]>:F[x],B](using: Handle[F2,A] => Pull[F2,B,Any]): Stream[F2,B] =
    Stream.pull(self: Stream[F2,A])(using)

  def repeatPull[F2[x]>:F[x],A2>:A,B](using: Handle[F2,A2] => Pull[F2,B,Handle[F2,A2]]): Stream[F2,B] =
    Stream.repeatPull(self: Stream[F2,A2])(using)

  def runFold[B](z: B)(f: (B,A) => B): Free[F,B] =
    Stream.runFold(self, z)(f)

  def runLog: Free[F,Vector[A]] =
    Stream.runFold(self, Vector.empty[A])(_ :+ _)
}
