package fs2.util

import scala.concurrent.ExecutionContext

object ExecutionContexts {

  implicit class ExecutionContextSyntax(private val self: ExecutionContext) extends AnyVal {
    def executeThunk[A](a: => A): Unit = self.execute(new Runnable { def run = { a; () } })
  }

  val sequential: ExecutionContext = new ExecutionContext {
    def execute(r: Runnable): Unit = r.run
    def reportFailure(cause: Throwable): Unit = ExecutionContext.defaultReporter(cause)
  }
}
