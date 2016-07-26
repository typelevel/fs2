package fs2

import scala.concurrent.ExecutionContext

/** Provides a function for evaluating thunks, possibly asynchronously. */
trait Strategy {
  def apply(thunk: => Unit): Unit

  override def toString = "Strategy"
}

object Strategy {

  /** Default strategy for use in Scala.JS. */
  val default: Strategy = fromExecutionContext(scala.concurrent.ExecutionContext.Implicits.global)

  /** Create a `Strategy` from an `ExecutionContext`. */
  def fromExecutionContext(es: ExecutionContext): Strategy = new Strategy {
    def apply(thunk: => Unit): Unit =
      es.execute { new Runnable { def run = thunk }}
  }

  /**
   * A `Strategy` which executes its argument immediately in the calling thread,
   * blocking until it is finished evaluating.
   */
  def sequential: Strategy = new Strategy {
    def apply(thunk: => Unit): Unit =
      thunk
  }
}
