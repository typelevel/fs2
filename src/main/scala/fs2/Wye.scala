package fs2

import Async.Future
import Step.{#:}
import Stream.Handle
import fs2.{Pull => P}
import fs2.util.NotNothing

object wye {

  // type Wye[I,I2,+O] = ???
  // type Wye[F[_],I,I2,+O] =
  // s.interrupt(interruptSignal)
  // s.wye(s2)(wye.merge)

  // trait Wye[-I,-I2,+O] {
  //   def run[F[_]:Async](s: Stream[F,I], s2: Stream[F,I2]): Stream[F,O]
  // }

  /** Like `[[merge]]`, but tags each output with the branch it came from. */
  def either[F[_]:Async,O,O2]
    : (Handle[F,O], Handle[F,O2]) =>
      Pull[F, Either[O,O2], (Handle[F,Either[O,O2]],Handle[F,Either[O,O2]])]
    = (s1, s2) => merge.apply(s1.map(Left(_)), s2.map(Right(_)))

  /**
   * Interleave the two inputs nondeterministically. The output stream
   * halts after BOTH `s1` and `s2` terminate normally, or in the event
   * of an uncaught failure on either `s1` or `s2`. Has the property that
   * `merge(Stream.empty, s) == s` and `merge(fail(e), s)` will
   * eventually terminate with `fail(e)`, possibly after emitting some
   * elements of `s` first.
   */
  def merge[F[_]:Async,O]: (Handle[F,O], Handle[F,O]) => Pull[F,O,(Handle[F,O],Handle[F,O])] = {
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
    (s1, s2) => s1.awaitAsync.flatMap { l => s2.awaitAsync.flatMap { r => go(l,r) }}
  }
}
