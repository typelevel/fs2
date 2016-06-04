package fs2

import fs2.util.{Free,Lub1,Monad,RealSupertype,Sub1,~>}

/**
 * Mixin trait for various non-primitive operations exposed on `Stream`
 * for syntactic convenience.
 */
private[fs2] trait StreamOps[+F[_],+A] extends StreamPipeOps[F,A] with StreamPipe2Ops[F,A] {
  self: Stream[F,A] =>

  import Stream.Handle

  // NB: methods in alphabetical order

  def ++[G[_],Lub[_],B>:A](s2: => Stream[G,B])(implicit R: RealSupertype[A,B], L: Lub1[F,G,Lub]): Stream[Lub,B] =
    Stream.append(Sub1.substStream(self)(L.subF), Sub1.substStream(s2)(L.subG))

  def attempt: Stream[F,Either[Throwable,A]] =
    self.map(Right(_)).onError(e => Stream.emit(Left(e)))

  def append[G[_],Lub[_],B>:A](s2: => Stream[G,B])(implicit R: RealSupertype[A,B], L: Lub1[F,G,Lub]): Stream[Lub,B] =
    Stream.append(Sub1.substStream(self)(L.subF), Sub1.substStream(s2)(L.subG))

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

  def evalMap[G[_],Lub[_],B](f: A => G[B])(implicit L: Lub1[F,G,Lub]): Stream[Lub,B] =
    Stream.flatMap(Sub1.substStream(self)(L.subF))(a => Sub1.substStream(Stream.eval(f(a)))(L.subG))

  def flatMap[G[_],Lub[_],B](f: A => Stream[G,B])(implicit L: Lub1[F,G,Lub]): Stream[Lub,B] =
    Stream.flatMap(Sub1.substStream(self)(L.subF))(a => Sub1.substStream(f(a))(L.subG))

  def mask: Stream[F,A] =
    Stream.mask(self)

  def map[B](f: A => B): Stream[F,B] =
    Stream.map(self)(f)

  def observe[F2[_],B>:A](sink: Sink[F2,B])(implicit F: Async[F2], R: RealSupertype[A,B], S: Sub1[F,F2]): Stream[F2,B] =
    async.channel.observe(Sub1.substStream(self)(S))(sink)

  def observeAsync[F2[_],B>:A](sink: Sink[F2,B], maxQueued: Int)(implicit F: Async[F2], R: RealSupertype[A,B], S: Sub1[F,F2]): Stream[F2,B] =
    async.channel.observeAsync(Sub1.substStream(self)(S), maxQueued)(sink)

  def onComplete[G[_],Lub[_],B>:A](regardless: => Stream[G,B])(implicit R: RealSupertype[A,B], L: Lub1[F,G,Lub]): Stream[Lub,B] =
    Stream.onComplete(Sub1.substStream(self)(L.subF), Sub1.substStream(regardless)(L.subG))

  def onError[G[_],Lub[_],B>:A](f: Throwable => Stream[G,B])(implicit R: RealSupertype[A,B], L: Lub1[F,G,Lub]): Stream[Lub,B] =
    Stream.onError(Sub1.substStream(self)(L.subF): Stream[Lub,B])(f andThen { g => Sub1.substStream(g)(L.subG) })

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

  def runFree:Free[F,Unit] =
    Stream.runFoldFree(self,())((_,_) => ())

  def runTraceFree(t: Trace):Free[F,Unit] =
    Stream.runFoldTraceFree(t)(self,())((_,_) => ())

  def runFoldFree[B](z: B)(f: (B,A) => B): Free[F,B] =
    Stream.runFoldFree(self, z)(f)

  def runFoldTraceFree[B](t: Trace)(z: B)(f: (B,A) => B): Free[F,B] =
    Stream.runFoldTraceFree(t)(self, z)(f)

  def runLogFree: Free[F,Vector[A]] =
    Stream.runFoldFree(self, Vector.empty[A])(_ :+ _)

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
