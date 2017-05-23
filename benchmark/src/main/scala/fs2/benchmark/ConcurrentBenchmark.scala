package fs2
package benchmark

import scala.concurrent.ExecutionContext.Implicits.global
import cats.effect.IO
import org.openjdk.jmh.annotations.{Benchmark, State, Scope}

@State(Scope.Thread)
class ConcurrentBenchmark {

  @GenerateN(1, 2, 4, 7, 16, 32, 64, 128, 256)
  @Benchmark
  def join(N: Int): Int = {
    val each = Stream.chunk(Chunk.seq(0 to 1000).map(i => Stream.eval(IO.pure(i))))
    Stream.join(N)(each).runLast.unsafeRunSync().get
  }
}
