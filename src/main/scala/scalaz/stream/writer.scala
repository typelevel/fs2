package  scalaz.stream

import scalaz.\/._
import scalaz.concurrent.Task
import scalaz.stream.Process._
import scalaz.{-\/, \/, \/-}

object writer {

  /** Promote a `Process` to a `Writer` that outputs nothing. */
  def liftW[F[_], W](p: Process[F, W]): Writer[F, W, Nothing] =
    p.map(left)

  /** Promote a `Process` to a `Writer` that writes nothing. */
  def liftO[F[_], O](p: Process[F, O]): Writer[F, Nothing, O] =
    p.map(right)

  /**
   * Promote a `Process` to a `Writer` that writes and outputs
   * all values of `p`.
   */
  def logged[F[_], A](p: Process[F, A]): Writer[F, A, A] =
    p.flatMap(a => Process.emitAll(Vector(left(a), right(a))))

}

/**
 * Infix syntax for working with `Writer[F,W,O]`. We call
 * the `W` parameter the 'write' side of the `Writer` and
 * `O` the 'output' side. Many method in this class end
 * with either `W` or `O`, depending on what side they
 * operate on.
 */
final class WriterSyntax[F[_], W, O](val self: Writer[F, W, O]) extends AnyVal {

  /** Ignore the output side of this `Writer`. */
  def drainO: Writer[F, W, Nothing] =
    flatMapO(_ => halt)

  /** Ignore the write side of this `Writer`. */
  def drainW: Writer[F, Nothing, O] =
    flatMapW(_ => halt)

  def flatMapO[F2[x] >: F[x], W2 >: W, B](f: O => Writer[F2, W2, B]): Writer[F2, W2, B] =
    self.flatMap(_.fold(emitW, f))

  /** Transform the write side of this `Writer`. */
  def flatMapW[F2[x] >: F[x], W2, O2 >: O](f: W => Writer[F2, W2, O2]): Writer[F2, W2, O2] =
    self.flatMap(_.fold(f, emitO))

  /** Map over the output side of this `Writer`. */
  def mapO[B](f: O => B): Writer[F, W, B] =
    self.map(_.map(f))

  /** Map over the write side of this `Writer`. */
  def mapW[W2](f: W => W2): Writer[F, W2, O] =
    self.map(_.leftMap(f))

  /**
   * Observe the output side of this `Writer` using the
   * given `Sink`, keeping it available for subsequent
   * processing. Also see `drainO`.
   */
  def observeO(snk: Sink[F, O]): Writer[F, W, O] =
    self.map(_.swap).observeW(snk).map(_.swap)

  /**
   * Observe the write side of this `Writer` using the
   * given `Sink`, keeping it available for subsequent
   * processing. Also see `drainW`.
   */
  def observeW(snk: Sink[F, W]): Writer[F, W, O] =
    self.zipWith(snk)((a,f) =>
      a.fold(
        (s: W) => eval_ { f(s) } ++ Process.emitW(s),
        (a: O) => Process.emitO(a)
      )
    ).flatMap(identity)

  /** Pipe output side of this `Writer`  */
  def pipeO[B](f: Process1[O, B]): Writer[F, W, B] =
    self.pipe(process1.liftR(f))

  /** Pipe write side of this `Writer`  */
  def pipeW[B](f: Process1[W, B]): Writer[F, B, O] =
    self.pipe(process1.liftL(f))

  /** Remove the output side of this `Writer`. */
  def stripO: Process[F, W] =
    self.flatMap(_.fold(emit, _ => halt))

  /** Remove the write side of this `Writer`. */
  def stripW: Process[F, O] =
    self.flatMap(_.fold(_ => halt, emit))
}

final class WriterTaskSyntax[W, O](val self: Writer[Task, W, O]) extends AnyVal {

  /**
   * Returns result of channel evaluation on `O` side tupled with
   * original output value passed to channel.
   */
  def observeOThrough[O2](ch: Channel[Task, O, O2]): Writer[Task, W, (O, O2)] = {
    val observerCh = ch map { f =>
      in: (W \/ O) => in.fold(w => Task.now(-\/(w)), o => f(o).map(o2 => \/-(o -> o2)))
    }
    self through observerCh
  }

  /** Returns result of channel evaluation on `W` side tupled with
    * original write value passed to channel.
    */
  def observeWThrough[W2](ch: Channel[Task, W, W2]): Writer[Task, (W, W2), O] = {
    val observerCh = ch map { f =>
      in: (W \/ O) => in.fold(w => f(w).map(w2 => -\/(w -> w2)), o => Task.now(\/-(o)))
    }
    self through observerCh
  }

  /** Feed this `Writer`'s output through the provided effectful `Channel`. */
  def throughO[O2](ch: Channel[Task, O, O2]): Writer[Task, W, O2] = {
    val ch2 = ch map { f =>
      in: (W \/ O) => in.fold(w => Task.now(left(w)), o => f(o).map(right))
    }
    self through ch2
  }

  /** Feed this `Writer`'s writes through the provided effectful `Channel`. */
  def throughW[W2](ch: Channel[Task, W, W2]): Writer[Task, W2, O] = {
    val ch2 = ch map { f =>
      in: (W \/ O) => in.fold(w => f(w).map(left), o => Task.now(right(o)))
    }
    self through ch2
  }

}
