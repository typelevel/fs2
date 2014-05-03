package scalaz.stream2

import scalaz.concurrent.Strategy
import scalaz.stream2.async.mutable.Signal
import scalaz.stream2.Process._

/**
 * Created by pach on 03/05/14.
 */
package object async {

  /**
   * Create a new continuous signal which may be controlled asynchronously.
   * All views into the returned signal are backed by the same underlying
   * asynchronous `Ref`.
   */
  def signal[A](implicit S: Strategy): Signal[A] = Signal(halt)


}
