package streams

trait Streams[S[+_[_],+_]] { self =>

  // list-like operations

  def empty[A]: S[Nothing,A] = emits[A](Chunk.empty)

  def emits[A](as: Chunk[A]): S[Nothing,A]

  def append[F[_],A](a: S[F,A], b: => S[F,A]): S[F,A]

  def flatMap[F[_],A,B](a: S[F,A])(f: A => S[F,B]): S[F,B]


  // evaluating effects

  def eval[F[_],A](fa: F[A]): S[F,A]


  // failure and error recovery

  def fail(e: Throwable): S[Nothing,Nothing]

  def onError[F[_],A](p: S[F,A])(handle: Throwable => S[F,A]): S[F,A]

  // resource acquisition

  def bracket[F[_],R,A](acquire: F[R])(use: R => S[F,A], release: R => F[Unit]): S[F,A]

  // stepping a stream

  type Handle[+F[_],+_]
  type Pull[+F[_],+R,+O]

  def pullMonad[F[_],W]: Monad[({ type f[x] = Pull[F,W,x]})#f]

  def write[F[_],W](p: S[F,W]): Pull[F,W,Unit]

  def runPull[F[_],W,R](p: Pull[F,W,R]): S[F,W]

  type AsyncStep[F[_],A] = F[Pull[F, Step[Chunk[A], S[F,A]], Nothing]]
  type AsyncStep1[F[_],A] = F[Pull[F, Step[A, S[F,A]], Nothing]]

  def await[F[_],A](h: Handle[F,A]): Pull[F, Step[Chunk[A], Handle[F,A]], Nothing]

  def await1[F[_],A](h: Handle[F,A]): Pull[F, Step[A, Handle[F,A]], Nothing]

  def awaitAsync[F[_],A](h: Handle[F,A])(implicit F: Async[F]): Pull[F, AsyncStep[F,A], Nothing]

  def await1Async[F[_],A](h: Handle[F,A])(implicit F: Async[F]): Pull[F, AsyncStep1[F,A], Nothing]

  def open[F[_],A](s: S[F,A]): Pull[F,Nothing,Handle[F,A]]

  // evaluation

  def runFold[F[_],A,B](p: S[F,A], z: B)(f: (B,A) => B): Free[F,Either[Throwable,B]]


  // derived operations

  def map[F[_],A,B](a: S[F,A])(f: A => B): S[F,B] =
    flatMap(a)(f andThen (emit))

  def emit[F[_],A](a: A): S[F,A] = emits(Chunk.singleton(a))

  def suspend[F[_],A](s: => S[F,A]): S[F,A] =
    flatMap(emit(())) { _ => try s catch { case t: Throwable => fail(t) } }

  def force[F[_],A](f: F[S[F, A]]): S[F,A] =
    flatMap(eval(f))(p => p)

  def eval_[F[_],A](fa: F[A]): S[F,Nothing] =
    flatMap(eval(fa)) { _ => empty }

  def terminated[F[_],A](p: S[F,A]): S[F,Option[A]] =
    p.map(Some(_)) ++ emit(None)

  def drain[F[_],A](p: S[F,A]): S[F,Nothing] =
    p flatMap { _ => empty }

  def onComplete[F[_],A](p: S[F,A], regardless: => S[F,A]): S[F,A] =
    onError(append(p, mask(regardless))) { err => append(mask(regardless), fail(err)) }

  def mask[F[_],A](a: S[F,A]): S[F,A] =
    onError(a)(_ => empty[A])

  implicit class StreamSyntax[+F[_],+A](p1: S[F,A]) {
    def map[B](f: A => B): S[F,B] =
      self.map(p1)(f)

    def flatMap[F2[x]>:F[x],B](f: A => S[F2,B]): S[F2,B] =
      self.flatMap(p1: S[F2,A])(f)

    def ++[F2[x]>:F[x],B>:A](p2: S[F2,B])(implicit R: RealSupertype[A,B]): S[F2,B] =
      self.append(p1: S[F2,B], p2)

    def append[F2[x]>:F[x],B>:A](p2: S[F2,B])(implicit R: RealSupertype[A,B]): S[F2,B] =
      self.append(p1: S[F2,B], p2)

    def onError[F2[x]>:F[x],B>:A](f: Throwable => S[F2,B])(implicit R: RealSupertype[A,B]): S[F2,B] =
      self.onError(p1: S[F2,B])(f)

    def runFold[B](z: B)(f: (B,A) => B): Free[F,Either[Throwable,B]] =
      self.runFold(p1, z)(f)

    def runLog: Free[F,Either[Throwable,Vector[A]]] =
      self.runFold(p1, Vector.empty[A])(_ :+ _)
  }

  implicit class HandleSyntax[+F[_],+A](h: Handle[F,A]) {
    def await: Pull[F, Step[Chunk[A], Handle[F,A]], Nothing] = self.await(h)
    def await1: Pull[F, Step[A, Handle[F,A]], Nothing] = self.await1(h)
    def awaitAsync[F2[x]>:F[x],A2>:A](implicit F2: Async[F2], A2: RealSupertype[A,A2]):
      Pull[F2, AsyncStep[F2,A2], Nothing] = self.awaitAsync(h)
    def await1Async[F2[x]>:F[x],A2>:A](implicit F2: Async[F2], A2: RealSupertype[A,A2]):
      Pull[F2, AsyncStep1[F2,A2], Nothing] = self.await1Async(h)
  }

  implicit class PullSyntax[+F[_],+W,+R](p: Pull[F,W,R]) {
    def map[R2](f: R => R2): Pull[F,W,R2] =
      self.pullMonad.map(p)(f)

    def flatMap[F2[x]>:F[x],W2>:W,R2](f: R => Pull[F2,W2,R2]): Pull[F2,W2,R2] =
      self.pullMonad.bind(p: Pull[F2,W2,R])(f)
  }
}

