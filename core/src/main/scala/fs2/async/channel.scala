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
    (f: Pipe[F,A, B])
    (qs: F[Queue[F,Option[Chunk[A]]]], g: Pipe[F,A,C])
    (combine: Pipe2[F,B,C,D])(implicit F: Async[F]): Stream[F,D]
    = Stream.eval(qs) flatMap { q =>
      def suspendf[A](a: => A) = F.map(F.pure(())) { _ => a }
      combine(
        f(
          s.repeatPull {
            _ receive { case a #: h => Pull.eval(q.enqueue1(Some(a))) >> Pull.output(a).as(h) }
          }.onFinalize(q.enqueue1(None))
        ),
        g(pipe.unNoneTerminate(q.dequeue) flatMap { c => Stream.chunk(c) })
      )
    }

  def joinQueued[F[_],A,B](q: F[Queue[F,Option[Chunk[A]]]])(s: Stream[F,Stream[F,A] => Stream[F,B]])(
    implicit F: Async[F]): Stream[F,A] => Stream[F,B] = {
    in => for {
      done <- Stream.eval(async.signalOf(false))
      q <- Stream.eval(q)
      b <- in.chunks.map(Some(_)).evalMap(q.enqueue1)
             .drain
             .onFinalize(q.enqueue1(None))
             .onFinalize(done.set(true)) merge done.interrupt(s).flatMap { f =>
               f(pipe.unNoneTerminate(q.dequeue) flatMap Stream.chunk)
             }
    } yield b
  }

  def joinAsync[F[_]:Async,A,B](maxQueued: Int)(s: Stream[F,Stream[F,A] => Stream[F,B]])
    : Stream[F,A] => Stream[F,B]
    = joinQueued[F,A,B](async.boundedQueue(maxQueued))(s)

  def join[F[_]:Async,A,B](s: Stream[F,Stream[F,A] => Stream[F,B]])
    : Stream[F,A] => Stream[F,B]
    = joinQueued[F,A,B](async.synchronousQueue)(s)

  /** Synchronously send values through `sink`. */
  def observe[F[_]:Async,A](s: Stream[F,A])(sink: Sink[F,A]): Stream[F,A] =
    diamond(s)(identity)(async.synchronousQueue, sink andThen (_.drain))(pipe2.merge)

  /** Send chunks through `sink`, allowing up to `maxQueued` pending _chunks_ before blocking `s`. */
  def observeAsync[F[_]:Async,A](s: Stream[F,A], maxQueued: Int)(sink: Sink[F,A]): Stream[F,A] =
    diamond(s)(identity)(async.boundedQueue(maxQueued), sink andThen (_.drain))(pipe2.merge)
}
