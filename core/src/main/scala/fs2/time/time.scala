package fs2

import java.util.concurrent.ScheduledExecutorService

import scala.concurrent.duration._

package object time {

  /**
   * Discrete stream that every `d` emits elapsed duration
   * since the start time of stream consumption.
   *
   * For example: `awakeEvery(5 seconds)` will
   * return (approximately) `5s, 10s, 20s`, and will lie dormant
   * between emitted values.
   *
   * By default, this uses a shared `ScheduledExecutorService`
   * for the timed events, and runs the consumer using the `S` `Strategy`,
   * to allow for the stream to decide whether result shall be run on
   * different thread pool, or with `Strategy.sequential` on the
   * same thread pool as the scheduler.
   *
   * @param d           Duration between emits of the resulting stream
   * @param scheduler   Scheduler used to schedule tasks
   */
  def awakeEvery[F[_]](d: Duration)(implicit F: Async[F], runner: Async.Run[F], scheduler: ScheduledExecutorService): Stream[F,Duration] = {
    def metronomeAndSignal: F[(()=>Unit,async.mutable.Signal[F,Duration])] = {
      F.bind(async.signalOf[F, Duration](Duration(0, NANOSECONDS))) { signal =>
        val t0 = Duration(System.nanoTime, NANOSECONDS)
        F.suspend {
          val metronome = scheduler.scheduleAtFixedRate(
            new Runnable { def run = {
              val d = Duration(System.nanoTime, NANOSECONDS) - t0
              runner.runEffects(signal.set(d))
              ()
            }},
            d.toNanos,
            d.toNanos,
            NANOSECONDS
          )
          (() => { metronome.cancel(false); () }, signal)
        }
      }
    }
    Stream.bracket(metronomeAndSignal)({ case (_, signal) => signal.discrete.drop(1) }, { case (cm, _) => F.suspend(cm()) })
  }

  /**
   * A continuous stream of the elapsed time, computed using `System.nanoTime`.
   * Note that the actual granularity of these elapsed times depends on the OS, for instance
   * the OS may only update the current time every ten milliseconds or so.
   */
  def duration[F[_]](implicit F: Async[F]): Stream[F, FiniteDuration] =
    Stream.eval(F.suspend(System.nanoTime)).flatMap { t0 =>
      Stream.repeatEval(F.suspend(FiniteDuration(System.nanoTime - t0, NANOSECONDS)))
    }

  /**
   * A continuous stream which is true after `d, 2d, 3d...` elapsed duration,
   * and false otherwise.
   * If you'd like a 'discrete' stream that will actually block until `d` has elapsed,
   * use `awakeEvery` instead.
   */
  def every[F[_]](d: Duration): Stream[F, Boolean] = {
    def go(lastSpikeNanos: Long): Stream[F, Boolean] =
      Stream.suspend {
        val now = System.nanoTime
        if ((now - lastSpikeNanos) > d.toNanos) Stream.emit(true) ++ go(now)
        else Stream.emit(false) ++ go(lastSpikeNanos)
      }
    go(0)
  }

  /**
   * A single-element `Stream` that waits for the duration `d`
   * before emitting its value. This uses a shared
   * `ScheduledThreadPoolExecutor` to signal duration and
   * avoid blocking on thread. After the signal,
   * the execution continues with `S` strategy.
   */
  def sleep[F[_]: Async : Async.Run](d: FiniteDuration)(implicit S: Strategy, schedulerPool: ScheduledExecutorService): Stream[F, Nothing] =
    awakeEvery(d).take(1).drain
}
