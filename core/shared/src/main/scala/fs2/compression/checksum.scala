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
package compression

import scodec.bits
import scodec.bits.crc.CrcBuilder
import scodec.bits.BitVector

/** Provides various checksums as pipes. */
object checksum {

  /** Computes a CRC32 checksum.
    * @see
    *   [[scodec.bits.crc.crc32]]
    */
  def crc32[F[_]]: Pipe[F, Byte, Byte] = fromCrcBuilder(bits.crc.crc32Builder)

  /** Computes a CRC32C checksum.
    * @see
    *   [[scodec.bits.crc.crc32c]]
    */
  def crc32c[F[_]]: Pipe[F, Byte, Byte] = fromCrcBuilder(bits.crc.crc32cBuilder)

  /** Computes a CRC checksum using the specified polynomial.
    * @see
    *   [[scodec.bits.crc]]
    */
  def crc[F[_]](
      poly: BitVector,
      initial: BitVector,
      reflectInput: Boolean,
      reflectOutput: Boolean,
      finalXor: BitVector
  ): Pipe[F, Byte, Byte] =
    fromCrcBuilder(bits.crc.builder(poly, initial, reflectInput, reflectOutput, finalXor))

  private def fromCrcBuilder[F[_]](builder: CrcBuilder[BitVector]): Pipe[F, Byte, Byte] =
    _.chunks
      .fold(builder)((builder, bits) => builder.updated(bits.toBitVector))
      .map(b => Chunk.byteVector(b.result.bytes))
      .unchunks

}
