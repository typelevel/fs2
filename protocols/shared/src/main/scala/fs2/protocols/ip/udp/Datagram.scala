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

// Adapted from scodec-protocols, licensed under 3-clause BSD

package fs2.protocols
package ip
package udp

import scodec.bits.BitVector
import scodec.Codec
import scodec.codecs._
import com.comcast.ip4s.Port

case class Datagram(sourcePort: Port, destinationPort: Port, data: BitVector)

object Datagram {
  implicit val codec: Codec[Datagram] = new Codec[Datagram] {
    def sizeBound = Codec[DatagramHeader].sizeBound.atLeast

    def encode(dg: Datagram) = for {
      encHeader <- Codec.encode(
        DatagramHeader(dg.sourcePort, dg.destinationPort, 8 + dg.data.bytes.size.toInt, 0)
      )
      chksum = Checksum.checksum(encHeader ++ dg.data)
    } yield encHeader.dropRight(16) ++ chksum ++ dg.data

    def decode(b: BitVector) = (for {
      header <- Codec[DatagramHeader]
      data <- bits(8L * (header.length - 8))
    } yield Datagram(header.sourcePort, header.destinationPort, data)).decode(b)
  }
}
