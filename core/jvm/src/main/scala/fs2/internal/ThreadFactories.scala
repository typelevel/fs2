/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

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
            if (exitJvmOnFatalError)
              e match {
                case NonFatal(_) => ()
                case _           => System.exit(-1)
              }
          }
        })
        t
      }
    }
}
