package fs2

import scala.concurrent.duration.FiniteDuration
import scala.scalajs.js.timers._


/** Provides the ability to schedule evaluation of thunks in the future. */
trait Scheduler {

  /**
   * Evaluates the thunk using the strategy after the delay.
   * Returns a thunk that when evaluated, cancels the execution.
   */
  def scheduleOnce(delay: FiniteDuration)(thunk: => Unit): () => Unit

  /**
   * Evaluates the thunk every period, using the strategy.
   * Returns a thunk that when evaluated, cancels the execution.
   */
  def scheduleAtFixedRate(period: FiniteDuration)(thunk: => Unit): () => Unit

  /**
   * Returns a strategy that executes all tasks after a specified delay.
   */
  def delayedStrategy(delay: FiniteDuration): Strategy = new Strategy {
    def apply(thunk: => Unit) = { scheduleOnce(delay)(thunk); () }
    override def toString = s"DelayedStrategy($delay)"
  }
}

object Scheduler {
  val default: Scheduler = new Scheduler {
    override def scheduleOnce(delay: FiniteDuration)(thunk: => Unit): () => Unit = {
      val handle = setTimeout(delay)(thunk)
      () => { clearTimeout(handle) }
    }
    override def scheduleAtFixedRate(period: FiniteDuration)(thunk: => Unit): () => Unit = {
      val handle = setInterval(period)(thunk)
      () => { clearInterval(handle) }
    }
    override def toString = "Scheduler"
  }
}
