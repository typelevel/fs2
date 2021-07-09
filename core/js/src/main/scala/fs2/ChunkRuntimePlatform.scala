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

import scala.scalajs.js.typedarray.ArrayBuffer
import scala.scalajs.js.typedarray.TypedArrayBuffer
import scala.scalajs.js.typedarray.Uint8Array
import scala.scalajs.js.typedarray.TypedArrayBufferOps._

trait ChunkRuntimePlatform[+O] { self: Chunk[O] =>

  def toJSArrayBuffer[B >: O](implicit ev: B =:= Byte): ArrayBuffer = {
    val bb = toByteBuffer[B]
    if (bb.hasArrayBuffer())
      bb.arrayBuffer()
    else {
      val ab = new ArrayBuffer(bb.remaining())
      TypedArrayBuffer.wrap(ab).put(bb)
      ab
    }
  }

  def toUint8Array[B >: O](implicit ev: B =:= Byte): Uint8Array = {
    val ab = toJSArrayBuffer[B]
    new Uint8Array(ab, 0, ab.byteLength)
  }

}

trait ChunkCompanionRuntimePlatform { self: Chunk.type =>

  def jsArrayBuffer(buffer: ArrayBuffer): Chunk[Byte] =
    byteBuffer(TypedArrayBuffer.wrap(buffer))

  def uint8Array(array: Uint8Array): Chunk[Byte] =
    jsArrayBuffer(array.buffer)

}
