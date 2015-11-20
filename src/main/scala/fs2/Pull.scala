package fs2

import collection.immutable.SortedSet
import fs2.internal.{LinkedSet,Trampoline}
import fs2.util.{Eq,Free,RealSupertype,Sub1}
import Stream.Token

trait Pull[+F[_],+W,+R] extends PullOps[F,W,R] {
  import Pull.Stack

  def run: Stream[F,W] = _run0(LinkedSet.empty, Pull.Stack.empty[F,W,R])

  private[fs2]
  final def _run0[F2[_],W2>:W,R1>:R,R2](tracked: LinkedSet[Token], k: Stack[F2,W2,R1,R2])(
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
  protected def _run1[F2[_],W2>:W,R1>:R,R2](tracked: LinkedSet[Token], k: Stack[F2,W2,R1,R2])(
    implicit S: Sub1[F,F2]): Stream[F2,W2]
}

object Pull extends Pulls[Pull] with PullDerived with pull1 with pull2 {
  type Stream[+F[_],+W] = fs2.Stream[F,W]

  val done: Pull[Nothing,Nothing,Nothing] = new Pull[Nothing,Nothing,Nothing] {
    type W = Nothing; type R = Nothing
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: LinkedSet[Token], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[Nothing,F2]): Stream[F2,W2]
      = {
      k (
        _ => runCleanup(tracked),
        new k.H[Stream[F2,W2]] { def f[x] = (segment, k) => segment (
          // done or p == p
          (or, eq) => Eq.substPull(or.run)(eq)._run0(tracked, k),
          // done onError h == done
          (handler,eq) => done._run0(tracked, k),
          // done flatMap f == done
          bind = _ => done._run0(tracked, k)
        )}
      )
      }
  }

  def fail(err: Throwable): Pull[Nothing,Nothing,Nothing] = new Pull[Nothing,Nothing,Nothing] {
    self =>
    type W = Nothing; type R = Nothing
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: LinkedSet[Token], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[Nothing,F2]): Stream[F2,W2]
      =
      k (
        _ => if (tracked.isEmpty) Stream.fail(err) else runCleanup(tracked) ++ Stream.fail(err),
        new k.H[Stream[F2,W2]] { def f[x] = (segment,k) => segment (
          // fail(e) or p == fail(e)
          (or, eq) => self._run0(tracked, k),
          // fail(e) onError h == h(e)
          (handler,eq) => Eq.substPull(handler(err))(eq)._run0(tracked, k),
          // fail(e) flatMap f == fail(e)
          bind = _ => self._run0(tracked, k)
        )}
      )
  }

  def pure[R](a: R): Pull[Nothing,Nothing,R] = new Pull[Nothing,Nothing,R] { self =>
    type W = Nothing
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: LinkedSet[Token], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[Nothing,F2]): Stream[F2,W2]
      =
      k (
        _ => runCleanup(tracked),
        new k.H[Stream[F2,W2]] { def f[x] = (segment,k) => segment (
          // pure(x) or p == pure(x)
          (or, eq) => Eq.substPull(self: Pull[Nothing,Nothing,R1])(eq)._run0(tracked, k),
          // pure(x) onError f == pure(x)
          (handler,eq) => Eq.substPull(self: Pull[Nothing,Nothing,R1])(eq)._run0(tracked, k),
          // pure(x) flatMap f == f(x)
          bindf => bindf(a)._run0(tracked, k)
        )}
      )
  }

  /**
   * Produce a `Pull` nonstrictly, catching exceptions. Behaves the same as
   * `pure(()).flatMap { _ => p }`.
   */
  def suspend[F[_],W,R](p: => Pull[F,W,R]): Pull[F,W,R] = new Pull[F,W,R] {
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: LinkedSet[Token], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[F,F2]): Stream[F2,W2]
      =
      try p._run0(tracked, k) catch { case t: Throwable => Stream.fail(t) }
  }

  def onError[F[_],W,R](p: Pull[F,W,R])(handle: Throwable => Pull[F,W,R]) = new Pull[F,W,R] {
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: LinkedSet[Token], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[F,F2]): Stream[F2,W2]
      = {
        val handle2: Throwable => Pull[F2,W,R] = handle andThen (Sub1.substPull(_))
        p._run0(tracked, k.pushHandler(handle2))
      }
  }

  def flatMap[F[_],W,R0,R](p: Pull[F,W,R0])(f: R0 => Pull[F,W,R]) = new Pull[F,W,R] {
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: LinkedSet[Token], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[F,F2]): Stream[F2,W2]
      = {
        val f2: R0 => Pull[F2,W,R] = f andThen (Sub1.substPull(_))
        p._run0[F2,W2,R0,R2](tracked, k.pushBind(f2))
      }
  }

  def eval[F[_],R](f: F[R]): Pull[F,Nothing,R] = new Pull[F,Nothing,R] {
    type W = Nothing
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: LinkedSet[Token], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[F,F2]): Stream[F2,W2]
      =
      Stream.eval(S(f)) flatMap { r => pure(r)._run0(tracked, k) }
  }

  def acquire[F[_],R](id: Token, r: F[R], cleanup: R => F[Unit]): Pull[F,Nothing,R] = new Pull[F,Nothing,R] {
    type W = Nothing
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: LinkedSet[Token], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[F,F2]): Stream[F2,W2]
      =
      Stream.flatMap (Sub1.substStream(Stream.acquire(id, r, cleanup))) {
        r => pure(r)._run0(tracked, k)
      }
  }

  def outputs[F[_],W](s: Stream[F,W]) = new Pull[F,W,Unit] {
    type R = Unit
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: LinkedSet[Token], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[F,F2]): Stream[F2,W2]
      =
      Sub1.substStream(s).append(pure(())._run1(tracked, k))
  }

  def or[F[_],W,R](p1: Pull[F,W,R], p2: => Pull[F,W,R]): Pull[F,W,R] = new Pull[F,W,R] {
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: LinkedSet[Token], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[F,F2]): Stream[F2,W2]
      =
      Sub1.substPull(p1)._run0(tracked, k.pushOr(() => Sub1.substPull(p2)))
  }

  def run[F[_],W,R](p: Pull[F,W,R]): Stream[F,W] = p.run

  private[fs2]
  def track(id: Token): Pull[Nothing,Nothing,Unit] = new Pull[Nothing,Nothing,Unit] {
    type W = Nothing; type R = Unit
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: LinkedSet[Token], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[Nothing,F2]): Stream[F2,W2]
      =
      pure(())._run0(tracked + id, k)
  }

  private[fs2]
  def release(id: Token): Pull[Nothing,Nothing,Unit] = new Pull[Nothing,Nothing,Unit] {
    type W = Nothing; type R = Unit
    def _run1[F2[_],W2>:W,R1>:R,R2](tracked: LinkedSet[Token], k: Stack[F2,W2,R1,R2])(
      implicit S: Sub1[Nothing,F2]): Stream[F2,W2]
      =
      Stream.release(id) ++ pure(())._run0(tracked - id, k)
  }

  sealed trait Segment[F[_],W,R1,R2] {
    def apply[K](or: (Trampoline[Pull[F,W,R1]], Eq[R1,R2]) => K,
                 handler: (Throwable => Pull[F,W,R1], Eq[R1,R2]) => K,
                 bind: (R1 => Pull[F,W,R2]) => K): K
  }
  object Segment {
    def Handler[F[_],W,R1](h: Throwable => Pull[F,W,R1]) = new Segment[F,W,R1,R1] {
      def apply[K](or: (Trampoline[Pull[F,W,R1]], Eq[R1,R1]) => K,
                   handler: (Throwable => Pull[F,W,R1], Eq[R1,R1]) => K,
                   bind: (R1 => Pull[F,W,R1]) => K): K = handler(h, Eq.refl)
    }
    def Or[F[_],W,R1](s: Trampoline[Pull[F,W,R1]]) = new Segment[F,W,R1,R1] {
      def apply[K](or: (Trampoline[Pull[F,W,R1]], Eq[R1,R1]) => K,
                   handler: (Throwable => Pull[F,W,R1], Eq[R1,R1]) => K,
                   bind: (R1 => Pull[F,W,R1]) => K): K = or(s, Eq.refl)
    }
    def Bind[F[_],W,R1,R2](f: R1 => Pull[F,W,R2]) = new Segment[F,W,R1,R2] {
      def apply[K](or: (Trampoline[Pull[F,W,R1]], Eq[R1,R2]) => K,
                   handler: (Throwable => Pull[F,W,R1], Eq[R1,R2]) => K,
                   bind: (R1 => Pull[F,W,R2]) => K): K = bind(f)
    }
  }

  private[fs2] trait Stack[F[_],W,R1,R2] { self =>
    def apply[K](empty: Eq[R1,R2] => K, segment: H[K]): K

    trait H[+K] { def f[x]: (Segment[F,W,R1,x], Stack[F,W,x,R2]) => K }

    def push[R0](s: Segment[F,W,R0,R1]): Stack[F,W,R0,R2] = new Stack[F,W,R0,R2] {
      def apply[K](empty: Eq[R0,R2] => K, segment: H[K]): K = segment.f[R1](s, self)
    }

    def pushBind[R0](f: R0 => Pull[F,W,R1]) = push(Segment.Bind(f))
    def pushHandler(f: Throwable => Pull[F,W,R1]) = push(Segment.Handler(f))
    def pushOr(s: () => Pull[F,W,R1]) = push(Segment.Or(Trampoline.delay(s())))
  }

  private[fs2] object Stack {
    def empty[F[_],W,R1]: Stack[F,W,R1,R1] = new Stack[F,W,R1,R1] {
      def apply[K](empty: Eq[R1,R1] => K, segment: H[K]): K = empty(Eq.refl)
    }
    def segment[F[_],W,R1,R2](s: Segment[F,W,R1,R2]): Stack[F,W,R1,R2] =
      empty.push(s)
  }
  private[fs2] def runCleanup(s: LinkedSet[Token]): Stream[Nothing,Nothing] =
    s.iterator.foldLeft(Stream.empty)((s,id) => Stream.append(Stream.release(id), s))
  private[fs2] def orRight[F[_],W,R](s: List[Pull[F,W,R]]): Pull[F,W,R] =
    s.reverse.foldLeft(done: Pull[F,W,R])((tl,hd) => or(hd,tl))
}
