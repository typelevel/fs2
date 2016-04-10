package fs2

import Async.Future
import Stream.Handle
import fs2.{Pull => P}

object wye {

  type Wye[F[_],-I,-I2,+O] = (Stream[F,I], Stream[F,I2]) => Stream[F,O]

  /**
   * Defined as `s1.drain merge s2`. Runs `s1` and `s2` concurrently, ignoring
   * any output of `s1`.
   */
  def mergeDrainL[F[_]:Async,I,I2](s1: Stream[F,I], s2: Stream[F,I2]): Stream[F,I2] =
    s1.drain merge s2

  /**
   * Defined as `s1 merge s2.drain`. Runs `s1` and `s2` concurrently, ignoring
   * any output of `s1`.
   */
  def mergeDrainR[F[_]:Async,I,I2](s1: Stream[F,I], s2: Stream[F,I2]): Stream[F,I] =
    s1 merge s2.drain

  /** Like `[[merge]]`, but tags each output with the branch it came from. */
  def either[F[_]:Async,I,I2](s1: Stream[F,I], s2: Stream[F,I2]): Stream[F,Either[I,I2]] =
    merge(s1.map(Left(_)), s2.map(Right(_)))

  /**
   * Let through the `s2` branch as long as the `s1` branch is `false`,
   * listening asynchronously for the left branch to become `true`.
   * This halts as soon as either branch halts.
   */
  def interrupt[F[_]:Async,I](s1: Stream[F,Boolean], s2: Stream[F,I]): Stream[F,I] =
    either(s1.noneTerminate, s2.noneTerminate)
      .takeWhile(_.fold(halt => halt.map(!_).getOrElse(false), o => o.isDefined))
      .collect { case Right(Some(i)) => i }

  /**
   * Interleave the two inputs nondeterministically. The output stream
   * halts after BOTH `s1` and `s2` terminate normally, or in the event
   * of an uncaught failure on either `s1` or `s2`. Has the property that
   * `merge(Stream.empty, s) == s` and `merge(fail(e), s)` will
   * eventually terminate with `fail(e)`, possibly after emitting some
   * elements of `s` first.
   */
  def merge[F[_]:Async,O](s1: Stream[F,O], s2: Stream[F,O]): Stream[F,O] = {
    def go(l: Future[F, Pull[F, Nothing, Step[Chunk[O], Handle[F,O]]]],
           r: Future[F, Pull[F, Nothing, Step[Chunk[O], Handle[F,O]]]]): Pull[F,O,Nothing] =
      (l race r).force flatMap {
        case Left(l) => l.optional flatMap {
          case None => r.force.flatMap(identity).flatMap { case hd #: tl => P.output(hd) >> P.echo(tl) }
          case Some(hd #: l) => P.output(hd) >> l.awaitAsync.flatMap(go(_, r))
        }
        case Right(r) => r.optional flatMap {
          case None => l.force.flatMap(identity).flatMap { case hd #: tl => P.output(hd) >> P.echo(tl) }
          case Some(hd #: r) => P.output(hd) >> r.awaitAsync.flatMap(go(l, _))
        }
      }
    s1.pull2(s2) {
      (s1,s2) => s1.awaitAsync.flatMap { l => s2.awaitAsync.flatMap { r => go(l,r) }}
    }
  }

  /** Like `merge`, but halts as soon as _either_ branch halts. */
  def mergeHaltBoth[F[_]:Async,O](s1: Stream[F,O], s2: Stream[F,O]): Stream[F,O] =
    s1.noneTerminate merge s2.noneTerminate pipe process1.unNoneTerminate

  /** Like `merge`, but halts as soon as the `s1` branch halts. */
  def mergeHaltL[F[_]:Async,O](s1: Stream[F,O], s2: Stream[F,O]): Stream[F,O] =
    s1.noneTerminate merge s2.map(Some(_)) pipe process1.unNoneTerminate

  /** Like `merge`, but halts as soon as the `s2` branch halts. */
  def mergeHaltR[F[_]:Async,O](s1: Stream[F,O], s2: Stream[F,O]): Stream[F,O] =
    mergeHaltL(s2, s1)
}
