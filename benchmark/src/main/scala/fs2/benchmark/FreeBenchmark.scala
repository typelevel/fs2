package fs2
package benchmark

import cats.effect.IO
import org.openjdk.jmh.annotations.{Benchmark, State, Scope}

import fs2.util.Free

@State(Scope.Thread)
class FreeBenchmark {

  val N = 1000000

  @Benchmark
  def nestedMaps = {
    val nestedMapsFree = (0 to N).foldLeft(Free.pure(0): Free[IO, Int]) { (acc, i) => acc.map(_ + i) }
    nestedMapsFree.run
  }

  @Benchmark
  def nestedFlatMaps = {
    val nestedFlatMapsFree = (0 to N).foldLeft(Free.pure(0): Free[IO, Int]) { (acc, i) => acc.flatMap(j => Free.pure(i + j)) }
    nestedFlatMapsFree.run
  }
}
