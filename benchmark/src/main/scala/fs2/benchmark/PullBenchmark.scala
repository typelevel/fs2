package fs2
package benchmark

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.openjdk.jmh.annotations.{Benchmark, Param, Scope, State}

@State(Scope.Thread)
class PullBenchmark {
  @Param(Array("8", "256"))
  var n: Int = _

  @Benchmark
  def unconsPull(): Int = {
    val s: Stream[Pure, Int] = Stream
      .chunk(Chunk.seq(0 to 2560))
      .repeatPull { s =>
        s.unconsN(n).flatMap {
          case Some((h, t)) => Pull.output(h).as(Some(t))
          case None         => Pull.pure(None)
        }
      }
    s
      .covary[IO]
      .compile
      .last
      .unsafeRunSync()
      .get
  }
}
