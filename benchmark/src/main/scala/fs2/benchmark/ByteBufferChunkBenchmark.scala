/*
 * Copyright (c) 2013 Functional Streams for Scala
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

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
