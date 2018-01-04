package fs2
package benchmark

import cats.effect.IO
import org.openjdk.jmh.annotations.{Benchmark, Scope, State}

import fs2.internal.FreeC

@State(Scope.Thread)
class FreeCBenchmark {

  val N = 1000000

  @Benchmark
  def nestedMaps = {
    val nestedMapsFreeC =
      (0 to N).foldLeft(FreeC.Pure[IO, Int](0): FreeC[IO, Int]) { (acc, i) =>
        acc.map(_ + i)
      }
    nestedMapsFreeC.run
  }

  @Benchmark
  def nestedFlatMaps = {
    val nestedFlatMapsFreeC =
      (0 to N).foldLeft(FreeC.Pure[IO, Int](0): FreeC[IO, Int]) { (acc, i) =>
        acc.flatMap(j => FreeC.Pure(i + j))
      }
    nestedFlatMapsFreeC.run
  }
}
