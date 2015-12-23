package fs2
package async

import mutable.Queue
import util.Monad
import Step._
import java.util.concurrent.atomic.AtomicLong

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
    (combine: (Stream[F,B], F[Int], Stream[F,C]) => Stream[F,D])(implicit F: Async[F]): Stream[F,D]
    = Stream.eval(qs) flatMap { q =>
      def suspendf[A](a: => A) = F.map(F.pure(())) { _ => a }
      combine(
        f(
          s.repeatPull { h => h.receive { case a #: h =>
            Pull.eval(q.enqueue1(Some(a))) >>
            Pull.output(a).as(h) }}
           .onComplete { Stream.eval_(q.enqueue1(None)) }
        ),
        q.size.get,
        g(toFiniteStream(q) flatMap { c => Stream.chunk(c) })
      )
    }

  /** Convert a `Queue[F,Option[A]]` to a stream by treating `None` as indicating end-of-stream. */
  def toFiniteStream[F[_],A](q: Queue[F,Option[A]]): Stream[F,A] = Stream.eval(q.dequeue1).flatMap {
    case None => Stream.empty
    case Some(a) => Stream.emit(a) ++ toFiniteStream(q)
  }

  def observe[F[_]:AsyncExt,A](s: Stream[F,A])(sink: Sink[F,A]): Stream[F,A] =
    diamond(s)(identity)(async.boundedQueue(1), sink) { (a,n,sinkResponses) =>
      (a repeatPull2 sinkResponses) { (h1, hq) =>
        Pull.eval(n) flatMap { numberQueued =>
          if (numberQueued >= 1) hq.receive { case _ #: hq => Pull.pure((h1,hq)) }
          else h1.receive { case a #: h1 => Pull.output(a).as((h1,hq)) }
        }
      }
    }

  def observeAsync[F[_]:AsyncExt,A](s: Stream[F,A])(sink: Sink[F,A], maxQueued: Int): Stream[F,A] =
    diamond(s)(identity)(async.boundedQueue(maxQueued), sink andThen (_.drain)) {
      (a,_,sinkResponses) => wye.merge(a,sinkResponses)
    }
}
