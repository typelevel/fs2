package fs2

import scala.concurrent.duration.FiniteDuration
import scala.scalajs.js.timers._

private[fs2] trait SchedulerPlatform {
  val default: Scheduler = new Scheduler {
    override def scheduleOnce(delay: FiniteDuration)(thunk: => Unit): () => Unit = {
      val handle = setTimeout(delay)(thunk)
      () =>
        { clearTimeout(handle) }
    }
    override def scheduleAtFixedRate(period: FiniteDuration)(thunk: => Unit): () => Unit = {
      val handle = setInterval(period)(thunk)
      () =>
        { clearInterval(handle) }
    }
    override def toString = "Scheduler"
  }
}
