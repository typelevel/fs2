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

package fs2.protocols
package pcapng

import pcap._
import scodec.Codec
import scodec.bits._
import scodec.codecs._

case class EnhancedPacketBlock(
    length: ByteVector,
    interfaceId: Long,
    timestampHigh: Long,
    timestampLow: Long,
    capturedPacketLength: Long,
    originalPacketLength: Long,
    bytes: ByteVector
) extends BodyBlock

object EnhancedPacketBlock {

  def hexConstant(implicit ord: ByteOrdering): ByteVector =
    Block.orderDependent(hex"00000006", hex"06000000")

  // format: off
  def codec(implicit ord: ByteOrdering): Codec[EnhancedPacketBlock] =
    "EPB" | Block.block(hexConstant) { length =>
      ("Interface ID" | guint32) ::
      ("Timestamp (High)" | guint32) ::
      ("Timestamp (Low)" | guint32) ::
      ("Captured Packet Length" | guint32) ::
      ("Original Packet Length" | guint32) ::
      fixedSizeBytes(Block.getLength(length) - 32, bytes)
    }.dropUnits.as[EnhancedPacketBlock]
  // format: on
}
