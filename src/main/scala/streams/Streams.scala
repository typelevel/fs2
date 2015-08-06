package streams

import streams.util.UF1

trait Streams[S[+_[_],+_]] { self =>

  // list-like operations

  def empty[A]: S[Nothing,A] = emits[A](Chunk.empty)

  def emits[A](as: Chunk[A]): S[Nothing,A]

  def append[F[_],A](a: S[F,A], b: => S[F,A]): S[F,A]

  def flatMap[F[_],A,B](a: S[F,A])(f: A => S[F,B]): S[F,B]


  // evaluating effects

  def eval[F[_],A](fa: F[A]): S[F,A]

  // translating effects

  def translate[F[_],G[_],W](s: S[F,W])(u: UF1[F,G]): S[G,W]

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

  type AsyncStep[F[_],A] = F[Pull[F, Nothing, Step[Chunk[A], Handle[F,A]]]]
  type AsyncStep1[F[_],A] = F[Pull[F, Nothing, Step[Option[A], Handle[F,A]]]]

  def push[F[_],A](h: Handle[F,A])(c: Chunk[A]): Handle[F,A]

  def await[F[_],A](h: Handle[F,A]): Pull[F, Nothing, Step[Chunk[A], Handle[F,A]]]

  def awaitAsync[F[_],A](h: Handle[F,A])(implicit F: Async[F]): Pull[F, Nothing, AsyncStep[F,A]]

  /**
   * Consult `p2` if `p` fails due to an `await` on an exhausted `Handle`.
   * If `p` fails due to an error, `p2` is not consulted.
   */
  def or[F[_],W,R](p: Pull[F, W, R], p2: => Pull[F,W,R]): Pull[F,W,R]

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

  def push1[F[_],A](h: Handle[F,A])(a: A): Handle[F,A] =
    push(h)(Chunk.singleton(a))

  def await1[F[_],A](h: Handle[F,A]): Pull[F, Nothing, Step[A, Handle[F,A]]] =
    h.await flatMap { case Step(hd, tl) => hd.uncons match {
      case None => await1(tl)
      case Some((h,hs)) => pullMonad.pure(Step(h, tl.push(hs)))
    }}

  def await1Async[F[_],A](h: Handle[F,A])(implicit F: Async[F]): Pull[F, Nothing, AsyncStep1[F,A]] =
    h.awaitAsync map { f =>
      F.map(f) { _.map { case Step(hd, tl) => hd.uncons match {
        case None => Step(None, tl)
        case Some((h,hs)) => Step(Some(h), tl.push(hs))
      }}}
    }

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
    def push[A2>:A](c: Chunk[A2])(implicit A2: RealSupertype[A,A2]): Handle[F,A2] =
      self.push(h: Handle[F,A2])(c)
    def push1[A2>:A](a: A2)(implicit A2: RealSupertype[A,A2]): Handle[F,A2] =
      self.push1(h: Handle[F,A2])(a)
    def await: Pull[F, Nothing, Step[Chunk[A], Handle[F,A]]] = self.await(h)
    def await1: Pull[F, Nothing, Step[A, Handle[F,A]]] = self.await1(h)
    def awaitAsync[F2[x]>:F[x],A2>:A](implicit F2: Async[F2], A2: RealSupertype[A,A2]):
      Pull[F2, Nothing, AsyncStep[F2,A2]] = self.awaitAsync(h)
    def await1Async[F2[x]>:F[x],A2>:A](implicit F2: Async[F2], A2: RealSupertype[A,A2]):
      Pull[F2, Nothing, AsyncStep1[F2,A2]] = self.await1Async(h)
  }

  implicit class PullSyntax[+F[_],+W,+R](p: Pull[F,W,R]) {

    def or[F2[x]>:F[x],W2>:W,R2>:R](p2: => Pull[F2,W2,R2])(
      implicit W2: RealType[W2], R2: RealType[R2])
      : Pull[F2,W2,R2]
      = self.or(p, p2)

    def map[R2](f: R => R2): Pull[F,W,R2] =
      self.pullMonad.map(p)(f)

    def flatMap[F2[x]>:F[x],W2>:W,R2](f: R => Pull[F2,W2,R2]): Pull[F2,W2,R2] =
      self.pullMonad.bind(p: Pull[F2,W2,R])(f)
  }
}

