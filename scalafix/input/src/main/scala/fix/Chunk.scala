/*
rule = v1
 */
package fix
import fs2._

trait ChunkRules {
  def s: Stream[Pure, String]

  val segments = s.segments
  val mapSegments = s.mapSegments(s => s)
  val scanSegments = s.scanSegments(0){case (s, seg) => seg.mapResult(_ => s)}
  val scanSegmentsOpt = s.scanSegmentsOpt(0)(_ => None)
  val unconsChunk = s.pull.unconsChunk
  val pullOutput = Pull.outputChunk(Chunk(1))
  def aSegment: Segment[Int, Unit]
}
