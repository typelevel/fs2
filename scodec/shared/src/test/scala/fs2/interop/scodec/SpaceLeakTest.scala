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

import _root_.scodec.codecs._

class SpaceLeakTest extends Fs2Suite {

  test("head of stream not retained") {
    // make sure that head of stream can be garbage collected
    // as we go; this also checks for stack safety
    val ints = variableSizeBytes(int32, vector(int32))
    val N = 400000L
    val M = 5
    val chunk = (0 until M).toVector
    val dec = StreamDecoder.many(ints)
    val source = Stream(ints.encode(chunk).require).repeat
    val actual =
      source.through(dec.toPipe[Fallible]).take(N).flatMap(Stream.emits(_)).compile.foldMonoid
    assert(actual == Right((0 until M).sum * N))
  }
}
