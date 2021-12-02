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

package fs2.protocols.pcapng

import scodec._
import scodec.bits._
import scodec.codecs._

object ByteOrderMagic extends Codec[ByteOrdering] {
  private val BigEndian = hex"1a2b3c4d"
  private val LittleEndian = hex"4d3c2b1a"

  def sizeBound = SizeBound.exact(32)

  def encode(bo: ByteOrdering) = bo match {
    case ByteOrdering.BigEndian => Attempt.successful(BigEndian.bits)
    case ByteOrdering.LittleEndian => Attempt.successful(LittleEndian.bits)
  }

  def decode(buf: BitVector) =
    bytes(4).decode(buf).flatMap {
      case DecodeResult(BigEndian, rest) =>
        Attempt.successful(DecodeResult(ByteOrdering.BigEndian, rest))
      case DecodeResult(LittleEndian, rest) =>
        Attempt.successful(DecodeResult(ByteOrdering.LittleEndian, rest))
      case DecodeResult(other, _) =>
        Attempt.failure(
          Err(s"unable to detect byte ordering due to unrecognized magic number $other")
        )
    }
}
