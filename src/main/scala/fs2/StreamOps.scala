package fs2

import process1.Process1
import fs2.util.{Free,RealSupertype,Sub1}

/**
 * Mixin trait for various non-primitive operations exposed on `Stream`
 * for syntactic convenience.
 */
private[fs2]
trait StreamOps[+F[_],+A] extends Process1Ops[F,A] /* with TeeOps[F,A] with WyeOps[F,A] */ {
  self: Stream[F,A] =>

  import Stream.Handle

  // NB: methods in alphabetical order

  def ++[F2[_],B>:A](p2: => Stream[F2,B])(implicit R: RealSupertype[A,B], S: Sub1[F,F2]): Stream[F2,B] =
    Stream.append(Sub1.substStream(self), p2)

  def append[F2[_],B>:A](p2: => Stream[F2,B])(implicit R: RealSupertype[A,B], S: Sub1[F,F2]): Stream[F2,B] =
    Stream.append(Sub1.substStream(self), p2)

  def covary[F2[_]](implicit S: Sub1[F,F2]): Stream[F2,A] =
    Sub1.substStream(self)

  def flatMap[F2[_],B](f: A => Stream[F2,B])(implicit S: Sub1[F,F2]): Stream[F2,B] =
    Stream.flatMap(Sub1.substStream(self))(f)

  def map[B](f: A => B): Stream[F,B] =
    Stream.map(self)(f)

  def onError[F2[_],B>:A](f: Throwable => Stream[F2,B])(implicit R: RealSupertype[A,B], S: Sub1[F,F2]): Stream[F2,B] =
    Stream.onError(Sub1.substStream(self): Stream[F2,B])(f)

  def open: Pull[F, Nothing, Handle[F,A]] = Stream.open(self)

  def pipe[B](f: Process1[_ >: A,B]): Stream[F,B] = self pull process1.covary(f)

  def pullv[F2[_],B](using: Handle[F,A] => Pull[F2,B,Any])(implicit S: Sub1[F,F2]): Stream[F2,B] =
    Stream.pull(self)(using)

  def repeatPull[F2[_],A2>:A,B](using: Handle[F2,A2] => Pull[F2,B,Handle[F2,A2]])(implicit S: Sub1[F,F2]): Stream[F2,B] =
    Stream.repeatPull(Sub1.substStream(self): Stream[F2,A2])(using)

  def runFold[B](z: B)(f: (B,A) => B): Free[F,B] =
    Stream.runFold(self, z)(f)

  def runLog: Free[F,Vector[A]] =
    Stream.runFold(self, Vector.empty[A])(_ :+ _)

  def tee[F2[_],B,C](s2: Stream[F2,B])(f: (Handle[F2,A], Handle[F2,B]) => Pull[F2,C,Any])(implicit S: Sub1[F,F2]): Stream[F2,C] =
    (Sub1.substStream(self)).open.flatMap {
      h1 => s2.open.flatMap { h2 => f(h1,h2) }
    }.run

  @deprecated("use `tee`, which now subsumes the functionality of `wye`", "0.9")
  def wye[F2[_],B,C](s2: Stream[F2,B])(f: (Handle[F2,A], Handle[F2,B]) => Pull[F2,C,Any])(implicit S: Sub1[F,F2]): Stream[F2,C] =
    tee(s2)(f)
}
