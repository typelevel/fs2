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
// Adapted from scodec-stream, licensed under 3-clause BSD

package fs2
package interop
package scodec

import cats.effect.SyncIO
import _root_.scodec.codecs._

// test for https://github.com/scodec/scodec-stream/issues/58
class ListOfNTest extends Fs2Suite {

  val codec = listOfN(uint16, uint16)
  val pipe = StreamDecoder.many(codec).toPipeByte[SyncIO]

  val ints = (1 to 7).toList
  val encodedBytes = Chunk.array(codec.encode(ints).require.toByteArray)

  test("non-split chunk") {
    val source = Stream.chunk(encodedBytes)
    val decodedList = source.through(pipe).compile.lastOrError.unsafeRunSync()
    assertEquals(decodedList, ints)
  }

  property("split chunk") {
    val (splitChunk1, splitChunk2) = encodedBytes.splitAt(6)
    val splitSource = Stream.chunk(splitChunk1) ++ Stream.chunk(splitChunk2)
    val decodedList = splitSource.through(pipe).compile.lastOrError.unsafeRunSync()
    assertEquals(decodedList, ints)
  }
}
