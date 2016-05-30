package fs2

import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.{ Executors, ScheduledExecutorService, TimeUnit }

/** Provides the ability to schedule evaluation of thunks in the future. */
trait Scheduler {

  /**
   * Evaluates the thunk using the strategy after the delay.
   * Returns a thunk that when evaluated, cancels the execution.
   */
  def scheduleOnce(delay: FiniteDuration)(thunk: => Unit)(implicit S: Strategy): () => Unit

  /**
   * Evaluates the thunk after the initial delay and then every period, using the strategy.
   * Returns a thunk that when evaluated, cancels the execution.
   */
  def scheduleAtFixedRate(initialDelay: FiniteDuration, period: FiniteDuration)(thunk: => Unit)(implicit S: Strategy): () => Unit

  /**
   * Returns a strategy that executes all tasks after a specified delay.
   */
  def delayedStrategy(delay: FiniteDuration)(implicit S: Strategy): Strategy = new Strategy {
    def apply(thunk: => Unit) = { scheduleOnce(delay)(thunk); () }
    override def toString = s"DelayedStrategy($delay)"
  }
}

object Scheduler {

  def fromFixedDaemonPool(corePoolSize: Int, threadName: String = "Scheduler.fromFixedDaemonPool"): Scheduler =
    fromScheduledExecutorService(Executors.newScheduledThreadPool(corePoolSize, Strategy.daemonThreadFactory(threadName)))

  def fromScheduledExecutorService(service: ScheduledExecutorService): Scheduler = new Scheduler {
    override def scheduleOnce(delay: FiniteDuration)(thunk: => Unit)(implicit S: Strategy): () => Unit = {
      val f = service.schedule(new Runnable { def run = S(thunk) }, delay.toNanos, TimeUnit.NANOSECONDS)
      () => { f.cancel(false); () }
    }
    override def scheduleAtFixedRate(initialDelay: FiniteDuration, period: FiniteDuration)(thunk: => Unit)(implicit S: Strategy): () => Unit = {
      val f = service.scheduleAtFixedRate(new Runnable { def run = S(thunk) }, initialDelay.toNanos, period.toNanos, TimeUnit.NANOSECONDS)
      () => { f.cancel(false); () }
    }
    override def toString = s"Scheduler($service)"
  }

}
