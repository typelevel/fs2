package fs2.internal

import java.lang.Thread.UncaughtExceptionHandler
import java.util.concurrent.{Executors, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

private[fs2] object ThreadFactories {
  /** A `ThreadFactory` which names threads according to the pattern ${threadPrefix}-${count}. */
  def named(
      threadPrefix: String,
      daemon: Boolean,
      exitJvmOnFatalError: Boolean = true
  ): ThreadFactory =
    new ThreadFactory {
      val defaultThreadFactory = Executors.defaultThreadFactory()
      val idx = new AtomicInteger(0)
      def newThread(r: Runnable) = {
        val t = defaultThreadFactory.newThread(r)
        t.setDaemon(daemon)
        t.setName(s"$threadPrefix-${idx.incrementAndGet()}")
        t.setUncaughtExceptionHandler(new UncaughtExceptionHandler {
          def uncaughtException(t: Thread, e: Throwable): Unit = {
            ExecutionContext.defaultReporter(e)
            if (exitJvmOnFatalError) {
              e match {
                case NonFatal(_) => ()
                case _           => System.exit(-1)
              }
            }
          }
        })
        t
      }
    }
}
