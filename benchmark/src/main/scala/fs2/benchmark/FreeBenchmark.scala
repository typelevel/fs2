package fs2
package benchmark

import org.openjdk.jmh.annotations.{Benchmark, State, Scope}

import fs2.util.Free

@State(Scope.Thread)
class FreeBenchmark extends BenchmarkUtils {

  val N = 1000000

  @Benchmark
  def nestedMaps = {
    val nestedMapsFree = (0 to N).foldLeft(Free.pure(0): Free[Task, Int]) { (acc, i) => acc.map(_ + i) }
    nestedMapsFree.run
  }

  @Benchmark
  def nestedFlatMaps = {
    val nestedFlatMapsFree = (0 to N).foldLeft(Free.pure(0): Free[Task, Int]) { (acc, i) => acc.flatMap(j => Free.pure(i + j)) }
    nestedFlatMapsFree.run
  }

  @Benchmark
  def alternatingMapFlatMap = {
    val nestedMapsFree = (0 to N).foldLeft(Free.pure(0): Free[Task, Int]) { (acc, i) => if (i % 2 == 0) acc.map(_ + i) else acc.flatMap(j => Free.pure(i + j))}
    nestedMapsFree.run
  }
}
