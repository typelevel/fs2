package fs2

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.effect.{ Async, Effect, IO }
import cats.implicits._

/**
 * Provides operations based on the passage of cpu time.
 *
 * Operations on this class generally return streams. Some operations return
 * effectful values instead. These operations are accessed via the `.effect`
 * method, which returns a projection consisting of operations that return
 * effects.
 */
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
   * Light weight alternative to `awakeEvery` that sleeps for duration `d` before each pulled element.
   */
  def awakeDelay[F[_]](d: FiniteDuration)(implicit F: Async[F], ec: ExecutionContext): Stream[F, FiniteDuration] =
    Stream.eval(F.delay(System.nanoTime)).flatMap { start =>
      fixedDelay[F](d) *> Stream.eval(F.delay((System.nanoTime - start).nanos))
    }

  /**
   * Discrete stream that every `d` emits elapsed duration
   * since the start time of stream consumption.
   *
   * For example: `awakeEvery[IO](5 seconds)` will
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
   * @param d FiniteDuration between emits of the resulting stream
   */
  def awakeEvery[F[_]](d: FiniteDuration)(implicit F: Effect[F], ec: ExecutionContext): Stream[F, FiniteDuration] =
    Stream.eval(F.delay(System.nanoTime)).flatMap { start =>
      fixedRate[F](d) *> Stream.eval(F.delay((System.nanoTime - start).nanos))
    }

  /**
   * Light weight alternative to [[fixedRate]] that sleeps for duration `d` before each pulled element.
   *
   * Behavior differs from `fixedRate` because the sleep between elements occurs after the next element
   * is pulled whereas `fixedRate` awakes every `d` regardless of when next element is pulled.
   * This difference can roughly be thought of as the difference between `scheduleWithFixedDelay` and
   * `scheduleAtFixedRate` in `java.util.concurrent.Scheduler`.
   *
   * Alias for `sleep(d).repeat`.
   */
  def fixedDelay[F[_]](d: FiniteDuration)(implicit F: Async[F], ec: ExecutionContext): Stream[F, Unit] =
    sleep(d).repeat

  /**
   * Discrete stream that emits a unit every `d`.
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
   * See [[fixedDelay]] for an alternative that sleeps `d` between elements.
   *
   * @param d FiniteDuration between emits of the resulting stream
   */
  def fixedRate[F[_]](d: FiniteDuration)(implicit F: Effect[F], ec: ExecutionContext): Stream[F, Unit] = {
    def metronomeAndSignal: F[(F[Unit],async.immutable.Signal[F,Unit])] = {
      for {
        signal <- async.signalOf[F, Unit](())
        result <- F.delay {
          // Note: we guard execution here because slow systems that are biased towards
          // scheduler threads can result in run away submission to the execution context.
          // This has happened with Scala.js, where the scheduler is backed by setInterval
          // and appears to be given priority over the tasks submitted to unsafeRunAsync.
          val running = new java.util.concurrent.atomic.AtomicBoolean(false)
          val cancel = scheduleAtFixedRate(d) {
            if (running.compareAndSet(false, true)) {
              async.unsafeRunAsync(signal.set(()))(_ => IO(running.set(false)))
            }
          }
          (F.delay(cancel()), signal)
        }
      } yield result
    }
    Stream.bracket(metronomeAndSignal)({ case (_, signal) => signal.discrete.drop(1) }, { case (cm, _) => cm })
  }

  /**
   * Returns a stream that when run, sleeps for duration `d` and then pulls from `s`.
   *
   * Alias for `sleep_[F](d) ++ s`.
   */
  def delay[F[_],O](s: Stream[F,O], d: FiniteDuration)(implicit F: Async[F], ec: ExecutionContext): Stream[F,O] =
    sleep_[F](d) ++ s

  /** Provides scheduler methods that return effectful values instead of streams. */
  def effect: Scheduler.EffectOps = new Scheduler.EffectOps(this)

  /**
   * A single-element `Stream` that waits for the duration `d` before emitting unit. This uses the implicit
   * `Scheduler` to signal duration and avoid blocking on thread. After the signal, the execution continues
   * on the supplied execution context.
   */
  def sleep[F[_]](d: FiniteDuration)(implicit F: Async[F], ec: ExecutionContext): Stream[F, Unit] =
    Stream.eval(effect.sleep(d))

  /**
   * Alias for `sleep(d).drain`. Often used in conjunction with `++` (i.e., `sleep_(..) ++ s`) as a more
   * performant version of `sleep(..) *> s`.
   */
  def sleep_[F[_]](d: FiniteDuration)(implicit F: Async[F], ec: ExecutionContext): Stream[F, Nothing] =
    sleep(d).drain

  /**
   * Retries `fa` on failure, returning a singleton stream with the
   * result of `fa` as soon as it succeeds.
   *
   * @param delay Duration of delay before the first retry
   *
   * @param nextDelay Applied to the previous delay to compute the
   *                  next, e.g. to implement exponential backoff
   *
   * @param maxRetries Number of attempts before failing with the
   *                   latest error, if `fa` never succeeds
   *
   * @param retriable Function to determine whether a failure is
   *                  retriable or not, defaults to retry every
   *                  `NonFatal`. A failed stream is immediately
   *                  returned when a non-retriable failure is
   *                  encountered
   */
  def retry[F[_], A](fa: F[A],
                     delay: FiniteDuration,
                     nextDelay: FiniteDuration => FiniteDuration,
                     maxRetries: Int,
                     retriable: Throwable => Boolean = internal.NonFatal.apply)(
    implicit F: Async[F], ec: ExecutionContext): Stream[F, A] = {
     val delays = Stream.unfold(delay)(d => Some(d -> nextDelay(d))).covary[F]

    attempts(Stream.eval(fa), delays)
      .take(maxRetries)
      .takeThrough(_.fold(err => retriable(err), _ => false))
      .last
      .map(_.getOrElse(sys.error("[fs2] impossible: empty stream in retry")))
      .rethrow
  }

  /**
   * Retries `s` on failure, returning a stream of attempts that can
   * be manipulated with standard stream operations such as `take`,
   * `collectFirst` and `interruptWhen`.
   *
   * Note: The resulting stream does *not* automatically halt at the
   * first successful attempt. Also see `retry`.
   */
  def attempts[F[_], A](s: Stream[F, A], delays: Stream[F, FiniteDuration])(
    implicit F: Async[F], ec: ExecutionContext): Stream[F, Either[Throwable, A]] =
    s.attempt ++ delays.flatMap(delay => sleep_(delay) ++ s.attempt)

  /**
   * Debounce the stream with a minimum period of `d` between each element.
   *
   * @example {{{
   * scala> import scala.concurrent.duration._, scala.concurrent.ExecutionContext.Implicits.global, cats.effect.IO
   * scala> val s2 = Scheduler[IO](1).flatMap { scheduler =>
   *      |   val s = Stream(1, 2, 3) ++ scheduler.sleep_[IO](500.millis) ++ Stream(4, 5) ++ scheduler.sleep_[IO](10.millis) ++ Stream(6)
   *      |   s.through(scheduler.debounce(100.milliseconds))
   *      | }
   * scala> s2.runLog.unsafeRunSync
   * res0: Vector[Int] = Vector(3, 6)
   * }}}
   */
  def debounce[F[_],O](d: FiniteDuration)(implicit F: Effect[F], ec: ExecutionContext): Pipe[F,O,O] = {
    def unconsLatest(s: Stream[F,O]): Pull[F,Nothing,Option[(O,Stream[F,O])]] =
      s.pull.uncons.flatMap {
        case Some((hd,tl)) => Pull.segment(hd.last).flatMap {
          case (_, Some(last)) => Pull.pure(Some(last -> tl))
          case (_, None) => unconsLatest(tl)
        }
        case None => Pull.pure(None)
      }

    def go(o: O, s: Stream[F,O]): Pull[F,O,Unit] = {
      sleep[F](d).pull.unconsAsync.flatMap { l =>
        s.pull.unconsAsync.flatMap { r =>
          (l race r).pull.flatMap {
            case Left(_) =>
              Pull.output1(o) *> r.pull.flatMap {
                case Some((hd,tl)) => Pull.segment(hd.last).flatMap {
                  case (_, Some(last)) => go(last, tl)
                  case (_, None) => unconsLatest(tl).flatMap {
                    case Some((last, tl)) => go(last, tl)
                    case None => Pull.done
                  }
                }
                case None => Pull.done
              }
            case Right(r) => r match {
              case Some((hd,tl)) => Pull.segment(hd.last).flatMap {
                case (_, Some(last)) => go(last, tl)
                case (_, None) => go(o, tl)
              }
              case None => Pull.output1(o)
            }
          }
        }
      }
    }
    in => unconsLatest(in).flatMap {
      case Some((last,tl)) => go(last, tl)
      case None => Pull.done
    }.stream
  }

  /**
   * Returns an execution context that executes all tasks after a specified delay.
   */
  def delayedExecutionContext(delay: FiniteDuration, reporter: Throwable => Unit = ExecutionContext.defaultReporter): ExecutionContext = new ExecutionContext {
    def execute(runnable: Runnable): Unit = { scheduleOnce(delay)(runnable.run); () }
    def reportFailure(cause: Throwable): Unit = reporter(cause)
    override def toString = s"DelayedExecutionContext($delay)"
  }
}

object Scheduler extends SchedulerPlatform {
  /** Operations on a scheduler which return effectful values. */
  final class EffectOps private[Scheduler] (private val scheduler: Scheduler) extends AnyVal {

    /**
     * Returns an action that when run, sleeps for duration `d` and then evaluates `fa`.
     *
     * Note: prefer `scheduler.delay(Stream.eval(fa), d)` over `Stream.eval(scheduler.effect.delay(fa, d))`
     * as the former can be interrupted while delaying.
     */
    def delay[F[_],A](fa: F[A], d: FiniteDuration)(implicit F: Async[F], ec: ExecutionContext): F[A] =
      sleep(d) *> fa

    /**
     * Starts a timer for duration `d` and after completion of the timer, evaluates `fa`.
     * Returns a "gate" value which allows semantic blocking on the result of `fa` and an action which cancels the timer.
     * If the cancellation action is invoked, the gate completes with `None`. Otherwise, the gate completes with `Some(a)`.
     */
    def delayCancellable[F[_],A](fa: F[A], d: FiniteDuration)(implicit F: Effect[F], ec: ExecutionContext): F[(F[Option[A]],F[Unit])] =
      async.ref[F,Option[A]].flatMap { gate =>
        F.delay {
          val cancel = scheduler.scheduleOnce(d) {
            ec.execute(() => async.unsafeRunAsync(fa.flatMap(a => gate.setAsyncPure(Some(a))))(_ => IO.unit))
          }
          gate.get -> (F.delay(cancel()) *> gate.setAsyncPure(None))
        }
      }

    /** Returns an action that when run, sleeps for duration `d` and then completes with `Unit`. */
    def sleep[F[_]](d: FiniteDuration)(implicit F: Async[F], ec: ExecutionContext): F[Unit] = {
      F.async[Unit] { cb =>
        scheduler.scheduleOnce(d) {
          ec.execute(() => cb(Right(())))
        }
        ()
      }
    }
  }
}
