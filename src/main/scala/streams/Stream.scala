package streams

import collection.immutable.LongMap

/**
 * A stream producing output of type `W`, which may evaluate `F`
 * effects. If `F` is `Nothing`, the stream is pure.
 */
trait Stream[+F[_],+W] {
  import Stream.Stack

  def runFold[O](g: (O,W) => O)(z: O): Free[F, Either[Throwable,O]] =
    runFold0_(0, LongMap.empty, Stream.emptyStack[F,W])(g, z)

  protected final def runFold0_[F2[_],O,W2>:W,W3](
    nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
    g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2, Either[Throwable,O]] =
    Free.pure(()) flatMap { _ => // trampoline after every step, catch exceptions
      try runFold1_(nextID, tracked, k)(g, z)
      catch { case t: Throwable => Stream.fail(t).runFold1_(nextID, tracked, k)(g,z) }
    }

  /**
   * The implementation of `runFold`. Not public. Note on parameters:
   *
   *   - `nextID` is used to generate fresh IDs
   *   - `tracked` is a map of the current in-scope finalizers,
   *     guaranteed to be run at most once before this `Stream` terminates
   *   - `k` is the stack of binds remaining. When empty, we obtain
   *     proof that `W2 == W3`, and can fold `g` over any emits.
   */
  protected def runFold1_[F2[_],O,W2>:W,W3](
    nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
    g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2, Either[Throwable,O]]
}

object Stream extends Streams[Stream] {

  def emits[W](c: Chunk[W]) = new Stream[Nothing,W] {
    type F[x] = Nothing
    def runFold1_[F2[_],O,W2>:W,W3](
      nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[Nothing,F2]): Free[F2, Either[Throwable,O]]
      =
      if (c.isEmpty) k (
        (_,_) => runCleanup(tracked) map (_ => Right(z)),
        new k.H[Free[F2,Either[Throwable,O]]] { def f[x] = (kh,k) =>
          if (kh.appends.isEmpty) // NB: kh.appends match triggers scalac bug
            empty.runFold0_(nextID, tracked, k)(g, z)
          else {
            val k2 = k.push[W2](kh.copy(appends = kh.appends.tail))
            kh.appends.head().runFold0_(nextID, tracked, k2)(g,z)
          }
        }
      )
      else k (
        (to,_) => empty.runFold0_(nextID, tracked, k)(g, c.foldLeft(z)((z,w) => g(z,to(w)))),
        new k.H[Free[F2,Either[Throwable,O]]] { def f[x] = (kh,k) => {
          val p = c.foldRight(empty[x]: Stream[F2,x])((w,px) => kh.bind(w) ++ px)
          p.runFold0_(nextID, tracked, k)(g, z)
        }}
      )
  }

  def fail(err: Throwable) = new Stream[Nothing,Nothing] { self =>
    def runFold1_[F2[_],O,W2>:Nothing,W3](
      nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[Nothing,F2]): Free[F2, Either[Throwable,O]]
      =
      k (
        (_,_) => runCleanup(tracked) map { _ => Left(err) },
        new k.H[Free[F2,Either[Throwable,O]]] { def f[x] = (kh,k) => {
          if (kh.handlers.isEmpty)
            self.runFold0_(nextID, tracked, k)(g, z)
          else {
            val kh2 = kh.copy(handlers = kh.handlers.tail)
            kh.handlers.head(err).runFold0_(nextID, tracked, kh2 +: k)(g,z)
          }
        }}
      )
  }

  def eval[F[_],W](f: F[W]) = new Stream[F,W] {
    def runFold1_[F2[_],O,W2>:W,W3](
      nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2, Either[Throwable,O]]
      =
      Free.eval(S(f)) flatMap { a => emit(a).runFold0_(nextID, tracked, k)(g, z) }
  }

  def flatMap[F[_],W0,W](s: Stream[F,W0])(f: W0 => Stream[F,W]) = new Stream[F,W] {
    def runFold1_[F2[_],O,W2>:W,W3](
      nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2, Either[Throwable,O]]
      = {
        val f2: W0 => Stream[F2,W] = f andThen (Sub1.substStream(_))
        s.runFold0_[F2,O,W0,W3](nextID, tracked, k.push(Frame(f2)))(g,z)
      }
  }

  def append[F[_],W](s: Stream[F,W], s2: => Stream[F,W]) = new Stream[F,W] {
    def runFold1_[F2[_],O,W2>:W,W3](
      nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2, Either[Throwable,O]]
      =
      s.runFold0_[F2,O,W2,W3](nextID, tracked, push(k, Sub1.substStream(s2)))(g,z)
  }

  private[streams] def scope[F[_],W](inner: Long => Stream[F,W]): Stream[F,W] = new Stream[F,W] {
    def runFold1_[F2[_],O,W2>:W,W3](
      nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2, Either[Throwable,O]]
      =
      inner(nextID).runFold0_(nextID+1, tracked, k)(g, z)
  }

  private[streams] def acquire[F[_],W](id: Long, r: Free[F, (W, F[Unit])]):
  Stream[F,W] = new Stream[F,W] {
    def runFold1_[F2[_],O,W2>:W,W3](
      nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2, Either[Throwable,O]]
      =
      Sub1.substFree(r) flatMap { case (r, cleanup) =>
        emit(r).runFold0_[F2,O,W2,W3](nextID, tracked updated (id, S(cleanup)), k)(g,z)
      }
  }

  private[streams] def release(id: Long): Stream[Nothing,Nothing] = new Stream[Nothing,Nothing] {
    type W = Nothing
    def runFold1_[F2[_],O,W2>:W,W3](
      nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[Nothing,F2]): Free[F2, Either[Throwable,O]]
      =
      tracked.get(id).map(Free.eval).getOrElse(Free.pure(())) flatMap { _ =>
        empty.runFold0_(nextID, tracked - id, k)(g, z)
      }
  }

  def onError[F[_],W](s: Stream[F,W])(handle: Throwable => Stream[F,W]) = new Stream[F,W] {
    def runFold1_[F2[_],O,W2>:W,W3](
      nextID: Long, tracked: LongMap[F2[Unit]], k: Stack[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2, Either[Throwable,O]]
      = {
        val handle2: Throwable => Stream[F2,W] = handle andThen (Sub1.substStream(_))
        s.runFold0_(nextID, tracked, push(k, handle2))(g, z)
      }
  }

  def bracket[F[_],R,W](r: F[R])(use: R => Stream[F,W], release: R => F[Unit]) =
    scope { id => acquire(id, Free.eval(r) map (r => (r, release(r)))) flatMap use }

  def open[F[_],W](s: Stream[F,W]) = Pull.pure(new Handle(s))

  type Pull[+F[_],+W,+R] = streams.Pull[F,W,R]

  def write[F[_],W](s: Stream[F,W]): Pull[F,W,Unit] = Pull.write(s)

  def runPull[F[_],W,R](p: Pull[F,W,R]) = p.run

  def runFold[F[_],W,O](s: Stream[F,W], z: O)(g: (O,W) => O) =
    s.runFold(g)(z)

  def pullMonad[F[_],W] = new Monad[({ type f[x] = Pull[F,W,x]})#f] {
    def pure[R](r: R) = Pull.pure(r)
    def bind[A,B](p: Pull[F,W,A])(f: A => Pull[F,W,B]) = Pull.flatMap(p)(f)
  }

  class Handle[+F[_],+W](private[streams] val stream: Stream[F,W])

  def push[F[_],W,W2](c: Stack[F,W,W2], p: => Stream[F,W]): Stack[F,W,W2] =
    ???
  def push[F[_],W,W2](c: Stack[F,W,W2], h: Throwable => Stream[F,W]): Stack[F,W,W2] =
    ???

  private def runCleanup[F[_]](l: LongMap[F[Unit]]): Free[F,Unit] =
    l.values.foldLeft[Free[F,Unit]](Free.pure(()))((tl,hd) =>
      Free.eval(hd) flatMap { _ => tl } )

  case class Frame[F[_],W1,W2](
    bind: W1 => Stream[F,W2],
    appends: List[() => Stream[F,W1]] = List(),
    handlers: List[Throwable => Stream[F,W1]] = List())

  private trait T[F[_]] { type f[a,b] = Frame[F,a,b] }
  type Stack[F[_],A,B] = streams.Chain[T[F]#f, A, B]

  private def emptyStack[F[_],A]: Stack[F,A,A] = streams.Chain.empty[T[F]#f, A]
}

