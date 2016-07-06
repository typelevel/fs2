package fs2

import scala.concurrent.duration.FiniteDuration
import scala.scalajs.js.timers._


/** Provides the ability to schedule evaluation of thunks in the future. */
trait Scheduler {

  /**
   * Evaluates the thunk using the strategy after the delay.
   * Returns a thunk that when evaluated, cancels the execution.
   */
  def scheduleOnce(delay: FiniteDuration)(thunk: => Unit)(implicit S: Strategy): () => Unit

  /**
   * Evaluates the thunk every period, using the strategy.
   * Returns a thunk that when evaluated, cancels the execution.
   */
  def scheduleAtFixedRate(period: FiniteDuration)(thunk: => Unit)(implicit S: Strategy): () => Unit

  /**
   * Returns a strategy that executes all tasks after a specified delay.
   */
  def delayedStrategy(delay: FiniteDuration)(implicit S: Strategy): Strategy = new Strategy {
    def apply(thunk: => Unit) = { scheduleOnce(delay)(thunk); () }
    override def toString = s"DelayedStrategy($delay)"
  }
}

object Scheduler {
  implicit val default: Scheduler = new Scheduler {
    override def scheduleOnce(delay: FiniteDuration)(thunk: => Unit)(implicit S: Strategy): () => Unit = {
      val handle = setTimeout(delay)(S(thunk))
      () => { clearTimeout(handle) }
    }
    override def scheduleAtFixedRate(period: FiniteDuration)(thunk: => Unit)(implicit S: Strategy): () => Unit = {
      val handle = setInterval(period)(S(thunk))
      () => { clearInterval(handle) }
    }
    override def toString = "Scheduler"
  }
}
