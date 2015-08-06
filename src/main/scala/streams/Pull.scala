package streams

import collection.immutable.SortedSet

trait Pull[+F[_],+W,+R] {
  import Pull.Stack

  def run: Stream[F,W] = _run0(SortedSet.empty, Pull.emptyStack[F,W,R])

  private[streams]
  final def _run0[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
    implicit S: Sub1[F,F2]): Stream[F2,W2]
    =
    Stream.suspend { _run1(tracked, k) }

  /**
   * The implementation of `run`. Not public. Note on parameters:
   *
   *   - `tracked` is a map of the current in-scope finalizers,
   *     guaranteed to be run at most once before this `Pull` terminates
   *   - `k` is the stack of work remaining.
   */
  protected def _run1[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
    implicit S: Sub1[F,F2]): Stream[F2,W2]
}

object Pull {

  val done: Pull[Nothing,Nothing,Nothing] = new Pull[Nothing,Nothing,Nothing] {
    type W = Nothing; type R = Nothing
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[Nothing,F2]): Stream[F2,W2]
      =
      k (
        (_,_) => runCleanup(tracked),
        new k.H[Stream[F2,W2]] { def f[x] = (kh,k) =>
          if (kh.ors.isEmpty) done._run0(tracked, k)
          else kh.ors.head()._run0(tracked, k push kh.copy(ors = kh.ors.tail))
        }
      )
  }

  def fail(err: Throwable): Pull[Nothing,Nothing,Nothing] = new Pull[Nothing,Nothing,Nothing] {
    type W = Nothing; type R = Nothing
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[Nothing,F2]): Stream[F2,W2]
      =
      k (
        (_,_) => runCleanup(tracked) ++ Stream.fail(err),
        new k.H[Stream[F2,W2]] { def f[x] = (kh,k) =>
          if (kh.handlers.isEmpty) fail(err)._run0(tracked, k)
          else kh.handlers.head(err)._run0(tracked, k push kh.copy(handlers = kh.handlers.tail))
        }
      )
  }

  def pure[R](a: R): Pull[Nothing,Nothing,R] = new Pull[Nothing,Nothing,R] {
    type W = Nothing
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[Nothing,F2]): Stream[F2,W2]
      =
      k (
        (_,_) => runCleanup(tracked),
        new k.H[Stream[F2,W2]] { def f[x] = (kh,k) =>
          kh.bind(a)._run0(tracked, k)
        }
      )
  }

  def suspend[F[_],W,R](p: => Pull[F,W,R]): Pull[F,W,R] =
    flatMap (pure(())) { _ => try p catch { case t: Throwable => fail(t) } }

  def onError[F[_],W,R](p: Pull[F,W,R])(handle: Throwable => Pull[F,W,R]) = new Pull[F,W,R] {
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[F,F2]): Stream[F2,W2]
      = {
        val handle2: Throwable => Pull[F2,W,R] = handle andThen (Sub1.substPull(_))
        p._run0(tracked, push(k, handle2))
      }
  }

  def flatMap[F[_],W,R0,R](p: Pull[F,W,R0])(f: R0 => Pull[F,W,R]) = new Pull[F,W,R] {
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[F,F2]): Stream[F2,W2]
      = {
        val f2: R0 => Pull[F2,W,R] = f andThen (Sub1.substPull(_))
        p._run0[F2,W2,R0,R2](tracked, k.push(Frame(f2)))
      }
  }

  def eval[F[_],R](f: F[R]) = new Pull[F,Nothing,R] {
    type W = Nothing
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[F,F2]): Stream[F2,W2]
      =
      Stream.eval(S(f)) flatMap { r => pure(r)._run0(tracked, k) }
  }

  def acquire[F[_],R](id: Long, r: F[R], cleanup: R => F[Unit]) = new Pull[F,Nothing,R] {
    type W = Nothing
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[F,F2]): Stream[F2,W2]
      =
      Stream.acquire(id, r, cleanup) flatMap { r => pure(r)._run0(tracked, k) }
  }

  def write[F[_],W](s: Stream[F,W]) = new Pull[F,W,Unit] {
    type R = Unit
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[F,F2]): Stream[F2,W2]
      =
      Sub1.substStream(s) ++ pure(())._run0(tracked, k)
  }

  def or[F[_],W,R](p1: Pull[F,W,R], p2: => Pull[F,W,R]): Pull[F,W,R] = new Pull[F,W,R] {
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[F,F2]): Stream[F2,W2]
      =
      Sub1.substPull(p1)._run0(tracked, push(k, Sub1.substPull(p2)))
  }

  private[streams]
  def scope[F[_],W,R](inner: Long => Pull[F,W,R]): Pull[F,W,R] = new Pull[F,W,R] {
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[F,F2]): Stream[F2,W2]
      =
      Stream.scope(id => Sub1.substPull(inner(id))._run0(tracked, k))
  }

  private[streams]
  def track(id: Long): Pull[Nothing,Nothing,Unit] = new Pull[Nothing,Nothing,Unit] {
    type W = Nothing; type R = Unit
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[Nothing,F2]): Stream[F2,W2]
      =
      pure(())._run0(tracked + id, k)
  }

  private[streams]
  def release(id: Long): Pull[Nothing,Nothing,Unit] = new Pull[Nothing,Nothing,Unit] {
    type W = Nothing; type R = Unit
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: SortedSet[Long], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[Nothing,F2]): Stream[F2,W2]
      =
      Stream.release(id) ++ pure(())._run0(tracked - id, k)
  }

  private[streams] def runCleanup(s: SortedSet[Long]): Stream[Nothing,Nothing] =
    s.iterator.foldLeft(Stream.empty)((s,id) => Stream.append(Stream.release(id), s))

  private[streams]
  case class Frame[F[_],W,R1,R2](
    bind: R1 => Pull[F,W,R2],
    ors: List[() => Pull[F,W,R1]] = List(),
    handlers: List[Throwable => Pull[F,W,R1]] = List())

  private trait T[F[_],W] { type f[a,b] = Frame[F,W,a,b] }
  private[streams]
  type Stack[F[_],W,A,B] = streams.Chain[T[F,W]#f, A, B]

  private[streams]
  def emptyStack[F[_],W,R] = streams.Chain.empty[T[F,W]#f,R]

  private[streams]
  def push[F[_],W,R1,R2](c: Stack[F,W,R1,R2], p: => Pull[F,W,R1]): Stack[F,W,R1,R2] =
    c.push { Frame(pure(_), List(() => p)) }

  private[streams]
  def push[F[_],W,R1,R2](c: Stack[F,W,R1,R2], h: Throwable => Pull[F,W,R1]): Stack[F,W,R1,R2] =
    c.push { Frame(pure(_), List(), List(h)) }
}
