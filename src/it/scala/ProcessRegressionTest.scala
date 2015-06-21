package scalaz.stream

import org.scalameter.api._

import scalaz.concurrent.Task

class ProcessRegressionTest extends PerformanceTest.OfflineRegressionReport {
  val sizes = Gen.range("size")(10000, 50000, 20000)
  val ones: Process[Task,Int] = Process.constant(1)
  val streams = for (size <- sizes) yield (ones.take(size))

  performance of "Process" in {
    measure method "run" in {
      using(streams) config (
        exec.independentSamples -> 1
      ) in { stream =>
        val u = stream.run.run
        u
      }
    }
  }
}
