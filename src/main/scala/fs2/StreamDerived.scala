package fs2

import Step.#:

/** Various derived operations that are mixed into the `Stream` companion object. */
private[fs2] trait StreamDerived { self: fs2.Stream.type =>

  def apply[W](a: W*): Stream[Nothing,W] = self.chunk(Chunk.seq(a))

  def writes[F[_],W](s: Stream[F,W]): Pull[F,W,Unit] = Pull.writes(s)

  def pull[F[_],A,B](s: Stream[F,A])(using: Handle[F,A] => Pull[F,B,Handle[F,A]])
  : Stream[F,B] = {
    def loop(h: Handle[F,A]): Pull[F,B,Unit] =
      using(h) flatMap (loop)
    Pull.run { open(s) flatMap loop }
  }

  def await1Async[F[_],A](h: Handle[F,A])(implicit F: Async[F]): Pull[F, Nothing, AsyncStep1[F,A]] =
    h.awaitAsync map { f =>
      F.map(f) { _.map { case Step(hd, tl) => hd.uncons match {
        case None => Step(None, tl)
        case Some((h,hs)) => Step(Some(h), tl.push(hs))
      }}}
    }

  def terminated[F[_],A](p: Stream[F,A]): Stream[F,Option[A]] =
    p.map(Some(_)) ++ emit(None)

  def drain[F[_],A](p: Stream[F,A]): Stream[F,Nothing] =
    p flatMap { _ => empty }

  def onComplete[F[_],A](p: Stream[F,A], regardless: => Stream[F,A]): Stream[F,A] =
    onError(append(p, mask(regardless))) { err => append(mask(regardless), fail(err)) }

  def mask[F[_],A](a: Stream[F,A]): Stream[F,A] =
    onError(a)(_ => empty[A])

  def map[F[_],A,B](a: Stream[F,A])(f: A => B): Stream[F,B] =
    Stream.map(a)(f)

  def mapChunked[F[_],A,B](a: Stream[F,A])(f: A => B): Stream[F,B] =
    // NB: I don't think this can be the default. It has to traverse
    // the whole stream `a` and thus isn't constant time,
    // unlike the `flatMap`-based version which has special support
    // in the interpreter.
    process1.lift(f)(a)

  def emit[F[_],A](a: A): Stream[F,A] = chunk(Chunk.singleton(a))

  def suspend[F[_],A](s: => Stream[F,A]): Stream[F,A] =
    flatMap(emit(())) { _ => try s catch { case t: Throwable => fail(t) } }

  def force[F[_],A](f: F[Stream[F, A]]): Stream[F,A] =
    flatMap(eval(f))(p => p)

  def eval_[F[_],A](fa: F[A]): Stream[F,Nothing] =
    flatMap(eval(fa)) { _ => empty }

  def push1[F[_],A](h: Handle[F,A])(a: A): Handle[F,A] =
    push(h)(Chunk.singleton(a))

  def peek[F[_],A](h: Handle[F,A]): Pull[F, Nothing, Step[Chunk[A], Handle[F,A]]] =
    h.await flatMap { case hd #: tl => Pull.pure(hd #: tl.push(hd)) }

  def await1[F[_],A](h: Handle[F,A]): Pull[F, Nothing, Step[A, Handle[F,A]]] =
    h.await flatMap { case Step(hd, tl) => hd.uncons match {
      case None => await1(tl)
      case Some((h,hs)) => Pull.pure(Step(h, tl push hs))
    }}

  def peek1[F[_],A](h: Handle[F,A]): Pull[F, Nothing, Step[A, Handle[F,A]]] =
    h.await1 flatMap { case hd #: tl => Pull.pure(hd #: tl.push1(hd)) }

  implicit class HandleSyntax[+F[_],+A](h: Handle[F,A]) {
    def push[A2>:A](c: Chunk[A2])(implicit A2: RealSupertype[A,A2]): Handle[F,A2] =
      self.push(h: Handle[F,A2])(c)
    def push1[A2>:A](a: A2)(implicit A2: RealSupertype[A,A2]): Handle[F,A2] =
      self.push1(h: Handle[F,A2])(a)
    def #:[H](hd: H): Step[H, Handle[F,A]] = Step(hd, h)
    def await: Pull[F, Nothing, Step[Chunk[A], Handle[F,A]]] = self.await(h)
    def await1: Pull[F, Nothing, Step[A, Handle[F,A]]] = self.await1(h)
    def peek: Pull[F, Nothing, Step[Chunk[A], Handle[F,A]]] = self.peek(h)
    def peek1: Pull[F, Nothing, Step[A, Handle[F,A]]] = self.peek1(h)
    def awaitAsync[F2[x]>:F[x],A2>:A](implicit F2: Async[F2], A2: RealSupertype[A,A2]):
      Pull[F2, Nothing, AsyncStep[F2,A2]] = self.awaitAsync(h)
    def await1Async[F2[x]>:F[x],A2>:A](implicit F2: Async[F2], A2: RealSupertype[A,A2]):
      Pull[F2, Nothing, AsyncStep1[F2,A2]] = self.await1Async(h)
  }
}
