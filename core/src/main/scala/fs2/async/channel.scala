package fs2
package async

import mutable.Queue

object channel {

  /**
   * Pass elements of `s` through both `f` and `g`, then combine the two resulting streams.
   * Implemented by enqueueing elements as they are seen by `f` onto a `Queue` used by the `g` branch.
   * USE EXTREME CARE WHEN USING THIS FUNCTION. Deadlocks are possible if `combine` pulls from the `g`
   * branch synchronously before the queue has been populated by the `f` branch.
   *
   * The `combine` function receives an `F[Int]` effect which evaluates to the current size of the
   * `g`-branch's queue.
   *
   * When possible, use one of the safe combinators like `[[observe]]`, which are built using this function,
   * in preference to using this function directly.
   */
  def diamond[F[_],A,B,C,D](s: Stream[F,A])
    (f: Stream[F,A] => Stream[F,B])
    (qs: F[Queue[F,Option[Chunk[A]]]], g: Stream[F,A] => Stream[F,C])
    (combine: (Stream[F,B], Stream[F,C]) => Stream[F,D])(implicit F: Async[F]): Stream[F,D]
    = Stream.eval(qs) flatMap { q =>
      def suspendf[A](a: => A) = F.map(F.pure(())) { _ => a }
      combine(
        f(
          Stream.bracket(F.pure(()))(
            _ => s.repeatPull { _ receive { case a #: h =>
              Pull.eval(q.enqueue1(Some(a))) >> Pull.output(a).as(h) }},
            _ => q.enqueue1(None)
          )
        ),
        g(process1.noneTerminate(q.dequeue) flatMap { c => Stream.chunk(c) })
      )
    }

  /** Synchronously send values through `sink`. */
  def observe[F[_]:Async,A](s: Stream[F,A])(sink: Sink[F,A]): Stream[F,A] =
    diamond(s)(identity)(async.synchronousQueue, sink andThen (_.drain)) { wye.merge(_,_) }

  /** Send chunks through `sink`, allowing up to `maxQueued` pending _chunks_ before blocking `s`. */
  def observeAsync[F[_]:Async,A](s: Stream[F,A])(sink: Sink[F,A], maxQueued: Int): Stream[F,A] =
    diamond(s)(identity)(async.boundedQueue(maxQueued), sink andThen (_.drain)) { wye.merge(_,_) }
}
