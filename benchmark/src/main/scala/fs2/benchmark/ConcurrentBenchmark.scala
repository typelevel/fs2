package fs2
package benchmark

import cats.effect.{ContextShift, IO}
import org.openjdk.jmh.annotations.{Benchmark, Param, Scope, State}

@State(Scope.Thread)
class ConcurrentBenchmark {
  implicit val cs: ContextShift[IO] =
    IO.contextShift(scala.concurrent.ExecutionContext.Implicits.global)

  @Param(Array("1", "16", "256"))
  var concurrent: Int = _

  @Benchmark
  def parJoin(): Int = {
    val each = Stream
      .range(0, 1000)
      .map(i => Stream.eval(IO.pure(i)))
      .covary[IO]
    each.parJoin(concurrent).compile.last.unsafeRunSync.get
  }
}
