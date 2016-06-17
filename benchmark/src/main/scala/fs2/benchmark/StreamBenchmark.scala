package fs2
package benchmark

import org.openjdk.jmh.annotations.{Benchmark, State, Scope}

@State(Scope.Thread)
class StreamBenchmark extends BenchmarkUtils {

  @GenerateN(2, 3, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 102400)
  @Benchmark
  def leftConcat(N: Int): List[Int] = {
    (1 until N).map(Stream.emit).foldRight(Stream.empty: Stream[Pure, Int])(_ ++ _).toList
  }

  @GenerateN(2, 3, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200, 102400)
  @Benchmark
  def rightConcat(N: Int): List[Int] = {
    Chunk.seq((0 until N).map(Stream.emit)).foldRight(Stream.empty: Stream[Pure,Int])(_ ++ _).toList
  }

}
