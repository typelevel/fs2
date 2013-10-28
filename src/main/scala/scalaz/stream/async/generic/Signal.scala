package scalaz.stream.async.generic

import scalaz.stream._
import scalaz.concurrent.Task
import scalaz.stream.Process._
import scalaz.\/._


trait Signal[A] {


  /**
   * Returns a continuous stream, indicating whether the value has changed. 
   * This will spike `true` once for each time the value of `Signal` was changed.
   * It will always spike with `true` when the process is run or when the `Signal` is
   * set for the first time. The first `true` may however come after several `false`,
   * when run or set for the first time
   *
   */
  lazy val changed: Process[Task, Boolean] =
    discrete.wye(continuous)(awaitL[A].map(left(_)) fby wye.either).map(_.isLeft)


  /**
   * Returns the discrete version of this signal, updated only when `value`
   * is changed.  Value may change several times between reads, but it is
   * guaranteed this will always get latest known value after any change. If you want
   * to be notified about every single change use `async.queue` for signalling.
   *
   * It will emit the current value of the Signal after being run or when the signal 
   * is set for the first time
   */
  def discrete: Process[Task, A]

  /**
   * Returns the continuous version of this signal, always equal to the 
   * current `A` inside `value`.
   */
  def continuous: Process[Task, A]

  /**
   * Returns the discrete version of `changed`. Will emit `Unit`
   * when the `value` is changed.
   * Will always emit `Unit` when the Signal is set for the first time or when this process is run. 
   */
  lazy val changes: Process[Task, Unit] = discrete.map(_ => ())


}

object Signal {

  val halted = new Signal[Nothing] {
    def discrete = halt

    def continuous = halt
  }

}
