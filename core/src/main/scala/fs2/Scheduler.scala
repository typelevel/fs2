package fs2

import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.{ Executors, ScheduledExecutorService, TimeUnit }

/** Provides the ability to schedule evaluation of thunks in the future. */
trait Scheduler {

  /**
   * Evaluates the specified thunk after the specified delay.
   * Returns a thunk that when evaluated, cancels the execution.
   */
  def scheduleOnce(delay: FiniteDuration)(thunk: => Unit): () => Unit

  /**
   * Evaluates the specified thunk after the specified initial delay and then every specified period.
   * Returns a thunk that cwen evaluated, cancels the execution.
   */
  def scheduleAtFixedRate(initialDelay: FiniteDuration, period: FiniteDuration)(thunk: => Unit): () => Unit

  /**
   * Returns a strategy that executes all tasks after a specified delay.
   */
  def delayedStrategy(delay: FiniteDuration): Strategy = new Strategy {
    def apply(thunk: => Unit) = { scheduleOnce(delay)(thunk); () }
    override def toString = s"DelayedStrategy($delay)"
  }
}

object Scheduler {

  def fromFixedDaemonPool(corePoolSize: Int, threadName: String = "Scheduler.fromFixedDaemonPool"): Scheduler =
    fromScheduledExecutorService(Executors.newScheduledThreadPool(corePoolSize, Strategy.daemonThreadFactory(threadName)))

  def fromScheduledExecutorService(service: ScheduledExecutorService): Scheduler = new Scheduler {
    override def scheduleOnce(delay: FiniteDuration)(thunk: => Unit): () => Unit = {
      val f = service.schedule(new Runnable { def run = thunk }, delay.toNanos, TimeUnit.NANOSECONDS)
      () => { f.cancel(false); () }
    }
    override def scheduleAtFixedRate(initialDelay: FiniteDuration, period: FiniteDuration)(thunk: => Unit): () => Unit = {
      val f = service.scheduleAtFixedRate(new Runnable { def run = thunk }, initialDelay.toNanos, period.toNanos, TimeUnit.NANOSECONDS)
      () => { f.cancel(false); () }
    }
    override def toString = s"Scheduler($service)"
  }

}
