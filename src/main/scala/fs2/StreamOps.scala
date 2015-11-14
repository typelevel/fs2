package fs2

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

  def drain: Stream[F, Nothing] =
    Stream.drain(self)

  /** Alias for `[[concurrent.either]](self, s2)`. */
  def either[F2[_]:Async,B](s2: Stream[F2,B])(implicit R: RealSupertype[A,B], S: Sub1[F,F2]): Stream[F2,Either[A,B]] =
    fs2.wye.either.apply(Sub1.substStream(self), s2)

  def evalMap[F2[_],B](f: A => F2[B])(implicit S: Sub1[F,F2]): Stream[F2,B] =
    Stream.flatMap(Sub1.substStream(self))(f andThen Stream.eval)

  def flatMap[F2[_],B](f: A => Stream[F2,B])(implicit S: Sub1[F,F2]): Stream[F2,B] =
    Stream.flatMap(Sub1.substStream(self))(f)

  def mask: Stream[F,A] =
    Stream.mask(self)

  def map[B](f: A => B): Stream[F,B] =
    Stream.map(self)(f)

  /** Alias for `[[concurrent.merge]](self, s2)`. */
  def merge[F2[_]:Async,B>:A](s2: Stream[F2,B])(implicit R: RealSupertype[A,B], S: Sub1[F,F2]): Stream[F2,B] =
    fs2.wye.merge.apply(Sub1.substStream(self), s2)

  def onComplete[F2[_],B>:A](regardless: => Stream[F2,B])(implicit R: RealSupertype[A,B], S: Sub1[F,F2]): Stream[F2,B] =
    Stream.onComplete(Sub1.substStream(self): Stream[F2,B], regardless)

  def onError[F2[_],B>:A](f: Throwable => Stream[F2,B])(implicit R: RealSupertype[A,B], S: Sub1[F,F2]): Stream[F2,B] =
    Stream.onError(Sub1.substStream(self): Stream[F2,B])(f)

  def open: Pull[F, Nothing, Handle[F,A]] = Stream.open(self)

  def output: Pull[F,A,Unit] = Pull.outputs(self)

  /** Transform this stream using the given `Process1`. */
  def pipe[B](f: Process1[A,B]): Stream[F,B] = process1.covary(f)(self)

  /** Like `pipe`, but the function may add additional effects. */
  def pipev[F2[_],B](f: Stream[F2,A] => Stream[F2,B])(implicit S: Sub1[F,F2]): Stream[F2,B] =
    f(Sub1.substStream(self))

  /** Like `pipe2`, but the function may add additional effects. */
  def pipe2v[F2[_],B,C](s2: Stream[F2,B])(f: (Stream[F2,A], Stream[F2,B]) => Stream[F2,C])(implicit S: Sub1[F,F2]): Stream[F2,C] =
    f(Sub1.substStream(self), s2)

  /** Like `pull`, but the function may add additional effects. */
  def pullv[F2[_],B](using: Handle[F,A] => Pull[F2,B,Any])(implicit S: Sub1[F,F2]): Stream[F2,B] =
    Stream.pull(self)(using)

  def runFold[B](z: B)(f: (B,A) => B): Free[F,B] =
    Stream.runFold(self, z)(f)

  def runLog: Free[F,Vector[A]] =
    Stream.runFold(self, Vector.empty[A])(_ :+ _)

  def tee[F2[_],B,C](s2: Stream[F2,B])(f: Tee[A,B,C])(implicit S: Sub1[F,F2]): Stream[F2,C] =
    pipe2v(s2)(fs2.tee.covary(f))

  def terminated: Stream[F,Option[A]] =
    Stream.terminated(self)

  @deprecated("use `pipe2` or `pipe2v`, which now subsumes the functionality of `wye`", "0.9")
  def wye[F2[_],B,C](s2: Stream[F2,B])(f: (Stream[F2,A], Stream[F2,B]) => Stream[F2,C])(implicit S: Sub1[F,F2])
  : Stream[F2,C] = pipe2v(s2)(f)
}
