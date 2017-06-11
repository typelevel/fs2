package fs2

import java.lang.Thread.UncaughtExceptionHandler
import java.util.concurrent.{ Executors, ScheduledExecutorService, ThreadFactory, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

import fs2.internal.NonFatal

/** Provides the ability to schedule evaluation of thunks in the future. */
trait Scheduler {

  /**
   * Evaluates the thunk after the delay.
   * Returns a thunk that when evaluated, cancels the execution.
   */
  def scheduleOnce(delay: FiniteDuration)(thunk: => Unit): () => Unit

  /**
   * Evaluates the thunk every period.
   * Returns a thunk that when evaluated, cancels the execution.
   */
  def scheduleAtFixedRate(period: FiniteDuration)(thunk: => Unit): () => Unit

  /**
   * Returns an execution context that executes all tasks after a specified delay.
   */
  def delayedExecutionContext(delay: FiniteDuration, reporter: Throwable => Unit = ExecutionContext.defaultReporter): ExecutionContext = new ExecutionContext {
    def execute(runnable: Runnable): Unit = { scheduleOnce(delay)(runnable.run); () }
    def reportFailure(cause: Throwable): Unit = reporter(cause)
    override def toString = s"DelayedExecutionContext($delay)"
  }
}

object Scheduler {

  def fromFixedDaemonPool(corePoolSize: Int, threadName: String = "Scheduler.fromFixedDaemonPool"): Scheduler =
    fromScheduledExecutorService(Executors.newScheduledThreadPool(corePoolSize, daemonThreadFactory(threadName)))

  def fromScheduledExecutorService(service: ScheduledExecutorService): Scheduler = new Scheduler {
    override def scheduleOnce(delay: FiniteDuration)(thunk: => Unit): () => Unit = {
      val f = service.schedule(new Runnable { def run = thunk }, delay.toNanos, TimeUnit.NANOSECONDS)
      () => { f.cancel(false); () }
    }
    override def scheduleAtFixedRate(period: FiniteDuration)(thunk: => Unit): () => Unit = {
      val f = service.scheduleAtFixedRate(new Runnable { def run = thunk }, period.toNanos, period.toNanos, TimeUnit.NANOSECONDS)
      () => { f.cancel(false); () }
    }
    override def toString = s"Scheduler($service)"
  }

  /** A `ThreadFactory` which creates daemon threads, using the given name. */
  private[fs2] def daemonThreadFactory(threadName: String, exitJvmOnFatalError: Boolean = true): ThreadFactory = new ThreadFactory {
    val defaultThreadFactory = Executors.defaultThreadFactory()
    val idx = new AtomicInteger(0)
    def newThread(r: Runnable) = {
      val t = defaultThreadFactory.newThread(r)
      t.setDaemon(true)
      t.setName(s"$threadName-${idx.incrementAndGet()}")
      t.setUncaughtExceptionHandler(new UncaughtExceptionHandler {
        def uncaughtException(t: Thread, e: Throwable): Unit = {
          ExecutionContext.defaultReporter(e)
          if (exitJvmOnFatalError) {
            e match {
              case NonFatal(_) => ()
              case fatal => System.exit(-1)
            }
          }
        }
      })
      t
    }
  }

}
