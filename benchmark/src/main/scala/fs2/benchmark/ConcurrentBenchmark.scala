package fs2
package benchmark

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations.{Benchmark, Param, Scope, State}

@State(Scope.Thread)
class ConcurrentBenchmark {

  @Param(Array("1", "16", "256"))
  var concurrent: Int = _

  @Benchmark
  def parJoin(): Int = {
    val each = Stream
      .range(0, 1000)
      .map(i => Stream.eval(IO.pure(i)))
      .covary[IO]
    each.parJoin(concurrent).compile.last.unsafeRunSync().get
  }
}
