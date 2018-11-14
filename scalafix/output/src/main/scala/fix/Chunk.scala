package fix
import fs2._

trait Chunk {
  def s: Stream[Pure, String]

  val segments = s.chunks
  val mapSegments = s.mapChunks(s => s)
  val scanSegments = s.scanChunks(0){case (s, seg) => seg.mapResult(_ => s)}
  val scanSegmentsOpt = s.scanChunksOpt(0)(_ => None)
  val unconsChunk = s.pull.uncons
  val pullOutput = Pull.output(Chunk(1))
}
