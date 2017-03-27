package fs2

import fs2.util.{Async,Attempt}
import fs2.util.syntax._

/** Provides utilities for working with streams concurrently. */
object concurrent {

  /**
    * Nondeterministically merges a stream of streams (`outer`) in to a single stream,
    * opening at most `maxOpen` streams at any point in time.
    *
    * The outer stream is evaluated and each resulting inner stream is run concurrently,
    * up to `maxOpen` stream. Once this limit is reached, evaluation of the outer stream
    * is paused until one or more inner streams finish evaluating.
    *
    * When the outer stream stops gracefully, all inner streams continue to run,
    * resulting in a stream that will stop when all inner streams finish
    * their evaluation.
    *
    * When the outer stream fails, evaluation of all inner streams is interrupted
    * and the resulting stream will fail with same failure.
    *
    * When any of the inner streams fail, then the outer stream and all other inner
    * streams are interrupted, resulting in stream that fails with the error of the
    * stream that cased initial failure.
    *
    * Finalizers on each inner stream are run at the end of the inner stream,
    * concurrently with other stream computations.
    *
    * Finalizers on the outer stream are run after all inner streams have been pulled
    * from the outer stream -- hence, finalizers on the outer stream will likely run
    * BEFORE the LAST finalizer on the last inner stream.
    *
    * Finalizers on the returned stream are run after the outer stream has finished
    * and all open inner streams have finished.
    *
    * @param maxOpen    Maximum number of open inner streams at any time. Must be > 0.
    * @param outer      Stream of streams to join.
    */
  def join[F[_],O](maxOpen: Int)(outer: Stream[F,Stream[F,O]])(implicit F: Async[F]): Stream[F,O] = {
    assert(maxOpen > 0, "maxOpen must be > 0, was: " + maxOpen)

    Stream.eval(async.signalOf(false)) flatMap { killSignal =>
    Stream.eval(async.semaphore(maxOpen)) flatMap { available =>
    Stream.eval(async.signalOf(1l)) flatMap { running => // starts with 1 because outer stream is running by default
    Stream.eval(async.mutable.Queue.synchronousNoneTerminated[F,Attempt[Chunk[O]]]) flatMap { outputQ => // sync queue assures we won't overload heap when resulting stream is not able to catchup with inner streams
      val incrementRunning: F[Unit] = running.modify(_ + 1) as (())
      val decrementRunning: F[Unit] = running.modify(_ - 1) as (())

      // runs inner stream
      // each stream is forked.
      // terminates when killSignal is true
      // if fails will enq in queue failure
      def runInner(inner: Stream[F, O]): Stream[F, Nothing] = {
        Stream.eval_(
          available.decrement >> incrementRunning >>
          F.start {
            inner.chunks.attempt
            .flatMap(r => Stream.eval(outputQ.enqueue1(Some(r))))
            .interruptWhen(killSignal) // must be AFTER enqueue to the the sync queue, otherwise the process may hang to enq last item while being interrupted
            .run.flatMap { _ =>
              available.increment >> decrementRunning
            }
          }
        )
      }

      // runs the outer stream, interrupts when kill == true, and then decrements the `available`
      def runOuter: F[Unit] = {
        outer.interruptWhen(killSignal) flatMap runInner onFinalize decrementRunning run
      }

      // monitors when the all streams (outer/inner) are terminated an then suplies None to output Queue
      def doneMonitor: F[Unit]= {
        running.discrete.dropWhile(_ > 0) take 1 flatMap { _ =>
          Stream.eval(outputQ.enqueue1(None))
        } run
      }

      Stream.eval_(F.start(runOuter)) ++
      Stream.eval_(F.start(doneMonitor)) ++
      outputQ.dequeue.unNoneTerminate.flatMap {
        case Left(e) => Stream.fail(e)
        case Right(c) => Stream.chunk(c)
      } onFinalize {
        killSignal.set(true) >> (running.discrete.dropWhile(_ > 0) take 1 run) // await all open inner streams and the outer stream to be terminated
      }

    }}}}

  }

}
