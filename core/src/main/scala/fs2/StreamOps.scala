package fs2

import fs2.util.{Free,Monad,RealSupertype,Sub1,~>}

/**
 * Mixin trait for various non-primitive operations exposed on `Stream`
 * for syntactic convenience.
 */
private[fs2] trait StreamOps[+F[_],+A] extends StreamPipeOps[F,A] with StreamPipe2Ops[F,A] {
  self: Stream[F,A] =>

  import Stream.Handle

  // NB: methods in alphabetical order

  def ++[F2[_],B>:A](p2: => Stream[F2,B])(implicit R: RealSupertype[A,B], S: Sub1[F,F2]): Stream[F2,B] =
    Stream.append(Sub1.substStream(self), p2)

  def attempt: Stream[F,Either[Throwable,A]] =
    self.map(Right(_)).onError(e => Stream.emit(Left(e)))

  def append[F2[_],B>:A](p2: => Stream[F2,B])(implicit R: RealSupertype[A,B], S: Sub1[F,F2]): Stream[F2,B] =
    Stream.append(Sub1.substStream(self), p2)

  /** Prepend a single chunk onto the front of this stream. */
  def cons[A2>:A](c: Chunk[A2])(implicit T: RealSupertype[A,A2]): Stream[F,A2] =
    Stream.cons[F,A2](self)(c)

  /** Prepend a single value onto the front of this stream. */
  def cons1[A2>:A](a: A2)(implicit T: RealSupertype[A,A2]): Stream[F,A2] =
    cons(Chunk.singleton(a))

  def covary[F2[_]](implicit S: Sub1[F,F2]): Stream[F2,A] =
    Sub1.substStream(self)

  def drain: Stream[F, Nothing] =
    Stream.drain(self)

  def evalMap[F2[_],B](f: A => F2[B])(implicit S: Sub1[F,F2]): Stream[F2,B] =
    Stream.flatMap(Sub1.substStream(self))(f andThen Stream.eval)

  def flatMap[F2[_],B](f: A => Stream[F2,B])(implicit S: Sub1[F,F2]): Stream[F2,B] =
    Stream.flatMap(Sub1.substStream(self))(f)

  def mask: Stream[F,A] =
    Stream.mask(self)

  def map[B](f: A => B): Stream[F,B] =
    Stream.map(self)(f)

  def onComplete[F2[_],B>:A](regardless: => Stream[F2,B])(implicit R: RealSupertype[A,B], S: Sub1[F,F2]): Stream[F2,B] =
    Stream.onComplete(Sub1.substStream(self): Stream[F2,B], regardless)

  def onError[F2[_],B>:A](f: Throwable => Stream[F2,B])(implicit R: RealSupertype[A,B], S: Sub1[F,F2]): Stream[F2,B] =
    Stream.onError(Sub1.substStream(self): Stream[F2,B])(f)

  def onFinalize[F2[_]](f: F2[Unit])(implicit S: Sub1[F,F2], F2: Monad[F2]): Stream[F2,A] =
    Stream.bracket(F2.pure(()))(_ => Sub1.substStream(self), _ => f)

  def open: Pull[F, Nothing, Handle[F,A]] = Stream.open(self)

  def output: Pull[F,A,Unit] = Pull.outputs(self)

  /** Like `pull`, but the function may add additional effects. */
  def pullv[F2[_],B](using: Handle[F,A] => Pull[F2,B,Any])(implicit S: Sub1[F,F2]): Stream[F2,B] =
    Stream.pull(self)(using)

  /** Repeat this stream an infinite number of times. `s.repeat == s ++ s ++ s ++ ...` */
  def repeat: Stream[F,A] = {
    self ++ repeat
  }

  def run:Free[F,Unit] =
    Stream.runFold(self,())((_,_) => ())

  def runTrace(t: Trace):Free[F,Unit] =
    Stream.runFoldTrace(t)(self,())((_,_) => ())

  def runFold[B](z: B)(f: (B,A) => B): Free[F,B] =
    Stream.runFold(self, z)(f)

  def runFoldTrace[B](t: Trace)(z: B)(f: (B,A) => B): Free[F,B] =
    Stream.runFoldTrace(t)(self, z)(f)

  def runLog: Free[F,Vector[A]] =
    Stream.runFold(self, Vector.empty[A])(_ :+ _)

  /** Like `through`, but the specified `Pipe`'s effect may be a supertype of `F`. */
  def throughv[F2[_],B](f: Pipe[F2,A,B])(implicit S: Sub1[F,F2]): Stream[F2,B] =
    f(Sub1.substStream(self))

  /** Like `through2`, but the specified `Pipe2`'s effect may be a supertype of `F`. */
  def through2v[F2[_],B,C](s2: Stream[F2,B])(f: Pipe2[F2,A,B,C])(implicit S: Sub1[F,F2]): Stream[F2,C] =
    f(Sub1.substStream(self), s2)

  /** Like `to`, but the specified `Sink`'s effect may be a supertype of `F`. */
  def tov[F2[_]](f: Sink[F2,A])(implicit S: Sub1[F,F2]): Stream[F2,Unit] =
    f(Sub1.substStream(self)).drain

  def translate[G[_]](u: F ~> G): Stream[G,A] = Stream.translate(self)(u)

  def noneTerminate: Stream[F,Option[A]] =
    Stream.noneTerminate(self)

  /** Converts a `Stream[Nothing,A]` in to a `Stream[Pure,A]`. */
  def pure(implicit S: Sub1[F,Pure]): Stream[Pure,A] =
    covary[Pure]

  @deprecated("renamed to noneTerminate", "0.9")
  def terminated: Stream[F,Option[A]] =
    Stream.noneTerminate(self)
}
