package fs2

import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}

import scala.concurrent.duration.FiniteDuration

import cats.effect.Sync

import fs2.internal.ThreadFactories

private[fs2] trait SchedulerPlatform {

  /** Returns a singleton stream consisting of a `Scheduler`. The scheduler is shutdown at finalization of the stream. */
  def apply[F[_]](corePoolSize: Int,
                  daemon: Boolean = true,
                  threadPrefix: String = "fs2-scheduler",
                  exitJvmOnFatalError: Boolean = true)(implicit F: Sync[F]): Stream[F, Scheduler] =
    Stream.bracket(allocate(corePoolSize, daemon, threadPrefix, exitJvmOnFatalError))(
      t => Stream.emit(t._1),
      _._2)

  /**
    * Allocates a scheduler in the specified effect type and returns the scheduler along with a shutdown
    * task, that when executed, terminates the thread pool used by the scheduler.
    */
  def allocate[F[_]](
      corePoolSize: Int,
      daemon: Boolean = true,
      threadPrefix: String = "fs2-scheduler",
      exitJvmOnFatalError: Boolean = true)(implicit F: Sync[F]): F[(Scheduler, F[Unit])] =
    F.delay {
      val executor = Executors.newScheduledThreadPool(
        corePoolSize,
        ThreadFactories.named(threadPrefix, daemon, exitJvmOnFatalError))
      fromScheduledExecutorService(executor) -> F.delay(executor.shutdown)
    }

  /** Creates a scheduler from the specified executor service. */
  def fromScheduledExecutorService(service: ScheduledExecutorService): Scheduler = new Scheduler {
    override def scheduleOnce(delay: FiniteDuration)(thunk: => Unit): () => Unit = {
      val f =
        service.schedule(new Runnable { def run = thunk }, delay.toNanos, TimeUnit.NANOSECONDS)
      () =>
        { f.cancel(false); () }
    }
    override def scheduleAtFixedRate(period: FiniteDuration)(thunk: => Unit): () => Unit = {
      val f = service.scheduleAtFixedRate(new Runnable { def run = thunk },
                                          period.toNanos,
                                          period.toNanos,
                                          TimeUnit.NANOSECONDS)
      () =>
        { f.cancel(false); () }
    }
    override def toString = s"Scheduler($service)"
  }
}
