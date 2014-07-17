package scalaz.stream.async.immutable

import scalaz.concurrent._
import scalaz.stream.Process


trait Signal[A] {
  self =>
  /**
   * Returns a continuous stream, indicating whether the value has changed.
   * This will spike `true` once for each time the value of `Signal` was changed.
   * It will always start with `true` when the process is run or when the `Signal` is
   * set for the first time.
   */
  def changed: Process[Task, Boolean]

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
   * current `A` inside `value`. Note that this may not see all changes of `A` as it
   * gets always current fresh `A` at every request.
   */
  def continuous: Process[Task, A]

  /**
   * Returns the discrete version of `changed`. Will emit `Unit`
   * when the `value` is changed.
   */
  def changes: Process[Task, Unit]
}
