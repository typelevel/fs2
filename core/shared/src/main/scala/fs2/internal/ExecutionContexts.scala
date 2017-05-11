package fs2.internal

import scala.concurrent.ExecutionContext

private[fs2] object ExecutionContexts {

  implicit class ExecutionContextSyntax(private val self: ExecutionContext) extends AnyVal {
    def executeThunk[A](a: => A): Unit = self.execute(new Runnable { def run = { a; () } })
  }
}
