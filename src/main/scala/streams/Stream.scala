package streams

import collection.immutable.LongMap
import Stream.SChain

/**
 * A stream producing output of type `W`, which may evaluate `F`
 * effects. If `F` is `Nothing`, the stream is pure.
 */
trait Stream[+F[_],+W] {
  def runFold[O](g: (O,W) => O)(z: O): Free[F, Either[Throwable,O]] =
    runFold0_(0, LongMap.empty, Stream.emptyChain[F,W])(g, z)

  def ++[F2[_],W2>:W](p: => Stream[F2,W2])(implicit S: Sub1[F,F2]): Stream[F2,W2] =
    ???

  protected final def runFold0_[F2[_],O,W2>:W,W3](
    nextID: Long, tracked: LongMap[F2[Unit]], k: SChain[F2,W2,W3])(
    g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2, Either[Throwable,O]] =
    Free.pure(()) flatMap { _ => // trampoline after every step
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
    nextID: Long, tracked: LongMap[F2[Unit]], k: SChain[F2,W2,W3])(
    g: (O,W3) => O, z: O)(implicit S: Sub1[F,F2]): Free[F2, Either[Throwable,O]]
}

object Stream {

  def empty[A]: Stream[Nothing,A] = emits(Chunk.empty)

  def emits[W](c: Chunk[W]): Stream[Nothing,W] = new Stream[Nothing,W] {
    type F[x] = Nothing
    def runFold1_[F2[_],O,W2>:W,W3](
      nextID: Long, tracked: LongMap[F2[Unit]], k: SChain[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[Nothing,F2]): Free[F2, Either[Throwable,O]]
      =
      if (c.isEmpty) k (
        (_,_) => runCleanup(tracked) map (_ => Right(z)),
        new k.H[Free[F2,Either[Throwable,O]]] { def f[x] = (kh,k) =>
          if (kh.appends.isEmpty) // NB: kh.appends match triggers scalac bug
            empty.runFold0_(nextID, tracked, k)(g, z)
          else {
            val k2 = k.push[W2](kh.copy(appends = kh.appends.tail))
            kh.appends.head.runFold0_(nextID, tracked, k2)(g,z)
          }
        }
      )
      else k (
        (to,_) => empty.runFold0_(nextID, tracked, k)(g, c.foldLeft(z)((z,w) => g(z,to(w)))),
        new k.H[Free[F2,Either[Throwable,O]]] { def f[x] = (kh,k) => {
          // todo - just do a strict right fold here
          val p = c.foldRight(empty[x]: Stream[F2,x])((w,px) => kh.bind(w) ++ px)
          p.runFold0_(nextID, tracked, k)(g, z)
        }}
      )
  }

  def fail[W](err: Throwable): Stream[Nothing,W] = new Stream[Nothing,W] { self =>
    def runFold1_[F2[_],O,W2>:W,W3](
      nextID: Long, tracked: LongMap[F2[Unit]], k: SChain[F2,W2,W3])(
      g: (O,W3) => O, z: O)(implicit S: Sub1[Nothing,F2]): Free[F2, Either[Throwable,O]]
      =
      k (
        (_,_) => runCleanup(tracked) map { _ => Left(err) },
        new k.H[Free[F2,Either[Throwable,O]]] { def f[x] = (kh,k) => {
          if (kh.handlers.isEmpty)
            fail(err).runFold0_(nextID, tracked, k)(g, z)
          else {
            val kh2 = kh.copy(handlers = kh.handlers.tail)
            kh.handlers.head(err).runFold0_(nextID, tracked, kh2 +: k)(g,z)
          }
        }}
      )
  }

  def push[F[_],W,W2](c: SChain[F,W,W2], p: Stream[F,W]): SChain[F,W,W2] =
    ???
  def push[F[_],W,W2](c: SChain[F,W,W2], h: Throwable => Stream[F,W]): SChain[F,W,W2] =
    ???

  private def runCleanup[F[_]](l: LongMap[F[Unit]]): Free[F,Unit] =
    l.values.foldLeft[Free[F,Unit]](Free.pure(()))((tl,hd) =>
      Free.eval(hd) flatMap { _ => tl } )

  case class P[F[_],W1,W2](
    bind: W1 => Stream[F,W2],
    appends: List[Stream[F,W1]],
    handlers: List[Throwable => Stream[F,W1]])

  private trait T[F[_]] { type f[a,b] = P[F,a,b] }
  type SChain[F[_],A,B] = streams.Chain[T[F]#f, A, B]

  private def emptyChain[F[_],A]: SChain[F,A,A] = streams.Chain.empty[T[F]#f, A]
}

