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
import typings.node.Buffer
import typings.node.bufferMod

import scala.scalajs.js.typedarray.{ArrayBuffer, TypedArrayBuffer, Uint8Array}
import scala.scalajs.js.typedarray.TypedArrayBufferOps._

private[fs2] object ByteChunkOps {
  implicit def toByteChunkOps(chunk: Chunk[Byte]): ByteChunkOps = new ByteChunkOps(chunk)
  implicit def toArrayBufferOps(arrayBuffer: ArrayBuffer): ArrayBufferOps = new ArrayBufferOps(
    arrayBuffer
  )
  implicit def toUint8ArrayOps(uint8Array: Uint8Array): Uint8ArrayOps = new Uint8ArrayOps(
    uint8Array
  )
  implicit def toBufferOps(buffer: Buffer): BufferOps = new BufferOps(buffer)

  private[fs2] final class ByteChunkOps(val chunk: Chunk[Byte]) extends AnyVal {

    def toArrayBuffer: ArrayBuffer = {
      val bb = chunk.toByteBuffer
      if (bb.hasArrayBuffer())
        bb.arrayBuffer()
      else {
        val ab = new ArrayBuffer(bb.remaining())
        TypedArrayBuffer.wrap(ab).put(bb)
        ab
      }
    }

    def toUint8Array: Uint8Array = {
      val ab = toArrayBuffer
      new Uint8Array(ab, 0, ab.byteLength)
    }

    def toBuffer: Buffer = bufferMod.Buffer.from(toArrayBuffer)

  }

  private[fs2] final class ArrayBufferOps(val arrayBuffer: ArrayBuffer) extends AnyVal {
    def toChunk: Chunk[Byte] = Chunk.byteBuffer(TypedArrayBuffer.wrap(arrayBuffer))
  }

  private[fs2] final class Uint8ArrayOps(val uint8Array: Uint8Array) extends AnyVal {
    def toChunk: Chunk[Byte] = uint8Array.buffer.toChunk
  }

  private[fs2] final class BufferOps(val buffer: Buffer) extends AnyVal {
    def toChunk: Chunk[Byte] = Chunk.byteBuffer(
      TypedArrayBuffer.wrap(
        buffer.buffer.slice(
          buffer.byteOffset.toInt,
          buffer.byteOffset.toInt + buffer.byteLength.toInt
        )
      )
    )
  }
}
