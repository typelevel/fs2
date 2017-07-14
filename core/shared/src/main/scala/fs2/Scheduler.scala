package fs2

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.effect.{ Async, Effect, IO, Sync }
import cats.implicits._

/** Provides the ability to schedule evaluation of thunks in the future. */
abstract class Scheduler {

  /**
   * Evaluates the thunk after the delay.
   * Returns a thunk that when evaluated, cancels the execution.
   */
  protected def scheduleOnce(delay: FiniteDuration)(thunk: => Unit): () => Unit

  /**
   * Evaluates the thunk every period.
   * Returns a thunk that when evaluated, cancels the execution.
   */
  protected def scheduleAtFixedRate(period: FiniteDuration)(thunk: => Unit): () => Unit

  /**
   * Discrete stream that every `d` emits elapsed duration
   * since the start time of stream consumption.
   *
   * For example: `awakeEvery(5 seconds)` will
   * return (approximately) `5s, 10s, 15s`, and will lie dormant
   * between emitted values.
   *
   * This uses an implicit `Scheduler` for the timed events, and
   * runs the consumer using the `F` `Effect[F]`, to allow for the
   * stream to decide whether result shall be run on different
   * thread pool.
   *
   * Note: for very small values of `d`, it is possible that multiple
   * periods elapse and only some of those periods are visible in the
   * stream. This occurs when the scheduler fires faster than
   * periods are able to be published internally, possibly due to
   * an execution context that is slow to evaluate.
   *
   * @param d           FiniteDuration between emits of the resulting stream
   */
  def awakeEvery[F[_]](d: FiniteDuration)(implicit F: Effect[F], ec: ExecutionContext): Stream[F, FiniteDuration] = {
    def metronomeAndSignal: F[(F[Unit],async.immutable.Signal[F,FiniteDuration])] = {
      for {
        signal <- async.signalOf[F, FiniteDuration](FiniteDuration(0, NANOSECONDS))
        result <- F.delay {
          val t0 = FiniteDuration(System.nanoTime, NANOSECONDS)
          // Note: we guard execution here because slow systems that are biased towards
          // scheduler threads can result in run away submission to the execution context.
          // This has happened with Scala.js, where the scheduler is backed by setInterval
          // and appears to be given priority over the tasks submitted to unsafeRunAsync.
          val running = new java.util.concurrent.atomic.AtomicBoolean(false)
          val cancel = scheduleAtFixedRate(d) {
            if (running.compareAndSet(false, true)) {
              val d = FiniteDuration(System.nanoTime, NANOSECONDS) - t0
              async.unsafeRunAsync(signal.set(d))(_ => IO(running.set(false)))
            }
          }
          (F.delay(cancel()), signal)
        }
      } yield result
    }
    Stream.bracket(metronomeAndSignal)({ case (_, signal) => signal.discrete.drop(1) }, { case (cm, _) => cm })
  }

  /**
   * A continuous stream of the elapsed time, computed using `System.nanoTime`.
   * Note that the actual granularity of these elapsed times depends on the OS, for instance
   * the OS may only update the current time every ten milliseconds or so.
   */
  def duration[F[_]](implicit F: Sync[F]): Stream[F, FiniteDuration] =
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
   * A single-element `Stream` that waits for the duration `d` before emitting unit. This uses the implicit
   * `Scheduler` to signal duration and avoid blocking on thread. After the signal, the execution continues
   * on the supplied execution context.
   */
  def sleep[F[_]](d: FiniteDuration)(implicit F: Async[F], ec: ExecutionContext): Stream[F, Unit] = {
    Stream.eval(F.async[Unit] { cb =>
      scheduleOnce(d) {
        ec.execute(() => cb(Right(())))
      }
      ()
    })
  }

  /** Identical to `sleep(d).drain`. */
  def sleep_[F[_]](d: FiniteDuration)(implicit F: Effect[F], ec: ExecutionContext): Stream[F, Nothing] =
    sleep(d).drain

  /**
   * Returns an execution context that executes all tasks after a specified delay.
   */
  def delayedExecutionContext(delay: FiniteDuration, reporter: Throwable => Unit = ExecutionContext.defaultReporter): ExecutionContext = new ExecutionContext {
    def execute(runnable: Runnable): Unit = { scheduleOnce(delay)(runnable.run); () }
    def reportFailure(cause: Throwable): Unit = reporter(cause)
    override def toString = s"DelayedExecutionContext($delay)"
  }
}

object Scheduler extends SchedulerPlatform
