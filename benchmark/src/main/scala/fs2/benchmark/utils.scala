package fs2
package benchmark

import fs2._

/** helper trait with utils */
trait BenchmarkUtils {
  /** 
    Number of cores. Note that hyperthreading causes this to be multiplied by 2,
    but isn't twice the performance 
    */
  val cores: Int = Runtime.getRuntime().availableProcessors
  /** strategy scaled to number of cores */
  lazy val scaledStrategy: Strategy = Strategy.fromFixedDaemonPool(cores)
}
