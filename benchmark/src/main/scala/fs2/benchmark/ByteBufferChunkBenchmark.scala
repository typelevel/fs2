package fs2
package benchmark

import org.openjdk.jmh.annotations.{Benchmark, Param, Scope, Setup, State}
import java.nio.ByteBuffer

@State(Scope.Thread)
class ByteBufferChunkBenchmark {
  @Param(Array("16", "256", "4096", "65536"))
  var size: Int = _

  var bbIndirect: ByteBuffer = _
  var bbDirect: ByteBuffer = _

  @Setup
  def setup() = {
    bbIndirect = ByteBuffer.allocate(size)
    bbDirect = ByteBuffer.allocate(size)
  }

  @Benchmark
  def bytesToArrayIndirect(): Array[Byte] =
    Chunk.bytes(bbIndirect.array).toArray

  @Benchmark
  def bytesToArrayDirect(): Array[Byte] =
    Chunk.bytes {
      val arr = new Array[Byte](bbDirect.remaining)
      bbDirect.slice.get(arr, 0, bbDirect.remaining)
      arr
    }.toArray

  @Benchmark
  def bytesToByteBufferIndirect(): ByteBuffer =
    Chunk.bytes(bbIndirect.array).toByteBuffer

  @Benchmark
  def bytesToByteBufferDirect(): ByteBuffer =
    Chunk.bytes {
      val arr = new Array[Byte](bbDirect.remaining)
      bbDirect.slice.get(arr, 0, bbDirect.remaining)
      arr
    }.toByteBuffer

  @Benchmark
  def byteBufferToArrayIndirect(): Array[Byte] =
    Chunk.byteBuffer(bbIndirect).toArray

  @Benchmark
  def byteBufferToArrayDirect(): Array[Byte] =
    Chunk.byteBuffer(bbDirect).toArray

  @Benchmark
  def byteBufferToByteBufferIndirect(): ByteBuffer =
    Chunk.byteBuffer(bbIndirect).toByteBuffer

  @Benchmark
  def byteBufferToByteBufferDirect(): ByteBuffer =
    Chunk.byteBuffer(bbDirect).toByteBuffer
}
