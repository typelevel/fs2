package fs2
package benchmark

import org.openjdk.jmh.annotations.{Benchmark, Param, Scope, Setup, State}

@State(Scope.Thread)
class ChunkBenchmark {
  @Param(Array("16", "256", "4096"))
  var chunkSize: Int = _

  @Param(Array("1", "20", "50", "100"))
  var chunkCount: Int = _

  case class Obj(dummy: Boolean)
  object Obj {
    def create: Obj = Obj(true)
  }

  var chunkSeq: Seq[Chunk[Obj]] = _
  var sizeHint: Int = _

  @Setup
  def setup() = {
    chunkSeq = Seq.range(0, chunkCount).map(_ => Chunk.seq(Seq.fill(chunkSize)(Obj.create)))
    sizeHint = chunkSeq.foldLeft(0)(_ + _.size)
  }

  @Benchmark
  def concat(): Unit =
    Chunk.concat(chunkSeq, sizeHint)
}
