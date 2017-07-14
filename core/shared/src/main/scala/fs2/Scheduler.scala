package fs2

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/** Provides the ability to schedule evaluation of thunks in the future. */
abstract class Scheduler {

  /**
   * Evaluates the thunk after the delay.
   * Returns a thunk that when evaluated, cancels the execution.
   */
  private[fs2] def scheduleOnce(delay: FiniteDuration)(thunk: => Unit): () => Unit

  /**
   * Evaluates the thunk every period.
   * Returns a thunk that when evaluated, cancels the execution.
   */
  private[fs2] def scheduleAtFixedRate(period: FiniteDuration)(thunk: => Unit): () => Unit

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
