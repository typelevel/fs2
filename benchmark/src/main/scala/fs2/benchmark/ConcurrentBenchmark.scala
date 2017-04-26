package fs2
package benchmark

import scala.concurrent.ExecutionContext.Implicits.global
import org.openjdk.jmh.annotations.{Benchmark, State, Scope}

@State(Scope.Thread)
class ConcurrentBenchmark {

  @GenerateN(1, 2, 4, 7, 16, 32, 64, 128, 256)
  @Benchmark
  def join(N: Int): Int = {
    val each = Stream.chunk(Chunk.seq(0 to 1000).map(i => Stream.eval(Task.now(i))))
    Stream.join(N)(each).runLast.unsafeRun().get
  }
}
