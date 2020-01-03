package fs2
package benchmark

import org.openjdk.jmh.annotations.{Benchmark, Param, Scope, State}

@State(Scope.Thread)
class RangeFoldBenchmark {
  @Param(Array("1", "4096", "665536", "67108863"))
  var n: Int = _

  @Benchmark
  def rangeFold(): Option[Long] =
    Stream.range(0, n).fold(0L)(_ + _.toLong).compile.last
}
