package fs2

import scala.concurrent.duration._

package object time {

  /**
   * Discrete stream that every `d` emits elapsed duration
   * since the start time of stream consumption.
   *
   * For example: `awakeEvery(5 seconds)` will
   * return (approximately) `5s, 10s, 15s`, and will lie dormant
   * between emitted values.
   *
   * This uses an implicit `Scheduler` for the timed events, and
   * runs the consumer using the `S` `Strategy`, to allow for the
   * stream to decide whether result shall be run on
   * different thread pool, or with `Strategy.sequential` on the
   * same thread pool as the scheduler.
   *
   * Note: for very small values of `d`, it is possible that multiple
   * periods elapse and only some of those periods are visible in the
   * stream. This occurs when the scheduler fires faster than
   * periods are able to be published internally, possibly due to
   * a `Strategy` that is slow to evaluate.
   *
   * @param d           FiniteDuration between emits of the resulting stream
   * @param S           Strategy to run the stream
   * @param scheduler   Scheduler used to schedule tasks
   */
  def awakeEvery[F[_]](d: FiniteDuration)(implicit F: Async[F], FR: Async.Run[F], S: Strategy, scheduler: Scheduler): Stream[F,FiniteDuration] = {
    def metronomeAndSignal: F[(()=>Unit,async.mutable.Signal[F,FiniteDuration])] = {
      F.bind(async.signalOf[F, FiniteDuration](FiniteDuration(0, NANOSECONDS))) { signal =>
        val lock = new java.util.concurrent.Semaphore(1)
        val t0 = FiniteDuration(System.nanoTime, NANOSECONDS)
        F.delay {
          val cancel = scheduler.scheduleAtFixedRate(d, d) {
            val d = FiniteDuration(System.nanoTime, NANOSECONDS) - t0
            if (lock.tryAcquire)
              FR.unsafeRunAsyncEffects(F.map(signal.set(d)) { _ => lock.release })(_ => ())
          }
          (cancel, signal)
        }
      }
    }
    Stream.bracket(metronomeAndSignal)({ case (_, signal) => signal.discrete.drop(1) }, { case (cm, _) => F.delay(cm()) })
  }

  /**
   * A continuous stream of the elapsed time, computed using `System.nanoTime`.
   * Note that the actual granularity of these elapsed times depends on the OS, for instance
   * the OS may only update the current time every ten milliseconds or so.
   */
  def duration[F[_]](implicit F: Async[F]): Stream[F, FiniteDuration] =
    Stream.eval(F.delay(System.nanoTime)).flatMap { t0 =>
      Stream.repeatEval(F.delay(FiniteDuration(System.nanoTime - t0, NANOSECONDS)))
    }

  /**
   * A continuous stream which is true after `d, 2d, 3d...` elapsed duration,
   * and false otherwise.
   * If you'd like a 'discrete' stream that will actually block until `d` has elapsed,
   * use `awakeEvery` instead.
   */
  def every[F[_]](d: FiniteDuration): Stream[F, Boolean] = {
    def go(lastSpikeNanos: Long): Stream[F, Boolean] =
      Stream.suspend {
        val now = System.nanoTime
        if ((now - lastSpikeNanos) > d.toNanos) Stream.emit(true) ++ go(now)
        else Stream.emit(false) ++ go(lastSpikeNanos)
      }
    go(0)
  }

  /**
   * A single-element `Stream` that waits for the duration `d` before emitting its value. This uses the implicit
   * `Scheduler` to signal duration and avoid blocking on thread. After the signal, the execution continues with `S` strategy.
   */
  def sleep[F[_]: Async : Async.Run](d: FiniteDuration)(implicit S: Strategy, scheduler: Scheduler): Stream[F, Nothing] =
    awakeEvery(d).take(1).drain
}
