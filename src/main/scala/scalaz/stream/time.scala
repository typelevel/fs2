package scalaz.stream

import java.util.concurrent.ScheduledExecutorService

import scala.concurrent.duration._
import scalaz.concurrent.{Strategy, Task}

import Process._

object time {

  /**
   * Discrete process that every `d` emits elapsed duration
   * since the start time of stream consumption.
   *
   * For example: `awakeEvery(5 seconds)` will
   * return (approximately) `5s, 10s, 20s`, and will lie dormant
   * between emitted values.
   *
   * By default, this uses a shared `ScheduledExecutorService`
   * for the timed events, and runs the consumer using the `pool` `Strategy`,
   * to allow for the process to decide whether result shall be run on
   * different thread pool, or with `Strategy.Sequential` on the
   * same thread pool as the scheduler.
   *
   * @param d           Duration between emits of the resulting process
   * @param S           Strategy to run the process
   * @param scheduler   Scheduler used to schedule tasks
   */
  def awakeEvery(d: Duration)(
    implicit S: Strategy,
    scheduler: ScheduledExecutorService): Process[Task, Duration] = {
    def metronomeAndSignal:(()=>Unit,async.mutable.Signal[Duration]) = {
      val t0 = Duration(System.nanoTime, NANOSECONDS)
      val signal = async.signalUnset[Duration](S)

      val metronome = scheduler.scheduleAtFixedRate(
        new Runnable { def run = {
          val d = Duration(System.nanoTime, NANOSECONDS) - t0
          signal.set(d).run
        }},
        d.toNanos,
        d.toNanos,
        NANOSECONDS
      )
      (()=>metronome.cancel(false), signal)
    }

    await(Task.delay(metronomeAndSignal))({
      case (cm, signal) =>  signal.discrete onComplete eval_(signal.close.map(_=>cm()))
    })
  }

  /**
   * A continuous stream of the elapsed time, computed using `System.nanoTime`.
   * Note that the actual granularity of these elapsed times depends on the OS, for instance
   * the OS may only update the current time every ten milliseconds or so.
   */
  def duration: Process[Task, FiniteDuration] =
    eval(Task.delay(System.nanoTime)).flatMap { t0 =>
      repeatEval(Task.delay(FiniteDuration(System.nanoTime - t0, NANOSECONDS)))
    }

  /**
   * A 'continuous' stream which is true after `d, 2d, 3d...` elapsed duration,
   * and false otherwise.
   * If you'd like a 'discrete' stream that will actually block until `d` has elapsed,
   * use `awakeEvery` instead.
   */
  def every(d: Duration): Process[Task, Boolean] = {
    def go(lastSpikeNanos: Long): Process[Task, Boolean] =
      suspend {
        val now = System.nanoTime
        if ((now - lastSpikeNanos) > d.toNanos) emit(true) ++ go(now)
        else emit(false) ++ go(lastSpikeNanos)
      }
    go(0)
  }

  /**
   * A single-element `Process` that waits for the duration `d`
   * before emitting its value. This uses a shared
   * `ScheduledThreadPoolExecutor` to signal duration and
   * avoid blocking on thread. After the signal,
   * the execution continues with `S` strategy
   */
  def sleep(d: FiniteDuration)(
    implicit S: Strategy
    , schedulerPool: ScheduledExecutorService
    ): Process[Task, Nothing] =
    awakeEvery(d).once.drain

}
