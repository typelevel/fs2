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

package fs2.io.internal

import fs2.Chunk
import fs2.internal.jsdeps.node.bufferMod

import scala.scalajs.js.typedarray.{ArrayBuffer, TypedArrayBuffer, Uint8Array}

private[fs2] object ByteChunkOps {
  implicit def toByteChunkOps(chunk: Chunk[Byte]): ByteChunkOps = new ByteChunkOps(chunk)
  implicit def toBufferOps(buffer: bufferMod.global.Buffer): BufferOps = new BufferOps(buffer)

  private[fs2] final class ByteChunkOps(val chunk: Chunk[Byte]) extends AnyVal {
    def toBuffer: bufferMod.global.Buffer = bufferMod.Buffer.from(toNodeUint8Array)
    def toNodeUint8Array: Uint8Array = chunk.toUint8Array.asInstanceOf[Uint8Array]
  }

  private[fs2] final class BufferOps(val buffer: bufferMod.global.Buffer) extends AnyVal {
    def toChunk: Chunk[Byte] = Chunk.byteBuffer(
      TypedArrayBuffer
        .wrap(
          buffer.buffer.asInstanceOf[ArrayBuffer],
          buffer.byteOffset.toInt,
          buffer.byteLength.toInt
        )
    )
  }
}
