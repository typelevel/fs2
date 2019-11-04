package fs2
package benchmark

import cats.effect.{ContextShift, IO}
import org.openjdk.jmh.annotations.{Benchmark, Scope, State}

@State(Scope.Thread)
class ConcurrentBenchmark {
  implicit val cs: ContextShift[IO] =
    IO.contextShift(scala.concurrent.ExecutionContext.Implicits.global)

  @GenerateN(1, 2, 4, 7, 16, 32, 64, 128, 256)
  @Benchmark
  def parJoin(N: Int): Int = {
    val each = Stream
      .range(0, 1000)
      .map(i => Stream.eval(IO.pure(i)))
      .covary[IO]
    each.parJoin(N).compile.last.unsafeRunSync.get
  }
}
