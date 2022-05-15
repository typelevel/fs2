package fs2.io.internal

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.concurrent.ExecutionContext
import scala.annotation.nowarn

private[io] object MicrotaskExecutor extends ExecutionContext {

  def execute(runnable: Runnable): Unit = queueMicrotask(() => runnable.run())

  def reportFailure(cause: Throwable): Unit = cause.printStackTrace()

  @JSGlobal("queueMicrotask")
  @js.native
  @nowarn
  private def queueMicrotask(function: js.Function0[Any]): Unit = js.native

}
