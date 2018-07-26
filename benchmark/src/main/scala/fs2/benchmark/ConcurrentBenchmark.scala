package fs2
package benchmark

import scala.concurrent.ExecutionContext.Implicits.global
import cats.effect.IO
import org.openjdk.jmh.annotations.{Benchmark, Scope, State}

@State(Scope.Thread)
class ConcurrentBenchmark {

  @GenerateN(1, 2, 4, 7, 16, 32, 64, 128, 256)
  @Benchmark
  def parJoin(N: Int): Int = {
    val each = Stream
      .segment(Segment.seq(0 to 1000).map(i => Stream.eval(IO.pure(i))))
      .covary[IO]
    each.parJoin(N).compile.last.unsafeRunSync.get
  }
}
