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
package tcp

import scodec.Codec
import scodec.codecs._
import fs2.interop.scodec._
import com.comcast.ip4s.Port

case class TcpHeader(
    sourcePort: Port,
    destinationPort: Port,
    sequenceNumber: Long,
    ackNumber: Long,
    dataOffset: Int,
    flags: TcpFlags,
    windowSize: Int,
    checksum: Int,
    urgentPointer: Int,
    options: List[Long]
)

object TcpHeader {
  // format:off
  implicit val codec: Codec[TcpHeader] = {
    ("source port" | Ip4sCodecs.port) ::
      ("destination port" | Ip4sCodecs.port) ::
      ("seqNumber" | uint32) ::
      ("ackNumber" | uint32) ::
      ("dataOffset" | uint4).flatPrepend { headerWords =>
        ("reserved" | ignore(4)) ::
          ("flags" | Codec[TcpFlags]) ::
          ("windowSize" | uint16) ::
          ("checksum" | uint16) ::
          ("urgentPointer" | uint16) ::
          ("options" | listOfN(provide(headerWords - 5), uint32))
      }
  }.dropUnits.as[TcpHeader]
  // format:on

  def sdecoder(protocol: Int): StreamDecoder[TcpHeader] =
    if (protocol == 6) StreamDecoder.once(codec)
    else StreamDecoder.empty
}
