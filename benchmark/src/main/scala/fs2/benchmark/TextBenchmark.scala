package fs2
package benchmark

import cats.effect.IO
import org.openjdk.jmh.annotations.{Benchmark, Param, Scope, Setup, State}

@State(Scope.Thread)
class TextBenchmark {
  @Param(Array("128", "1024", "4096"))
  var asciiStringSize: Int = _

  var asciiBytes: Array[Byte] = _
  @Setup
  def setup(): Unit = {
    val rng = new java.util.Random(7919)
    asciiBytes = (0 until asciiStringSize).map { _ =>
      (rng.nextInt(126) + 1).toByte
    }.toArray
  }

  @Benchmark
  def asciiDecode(): String =
    Stream
      .emits(asciiBytes)
      .through(text.utf8Decode[IO])
      .compile
      .last
      .unsafeRunSync
      .get
}
