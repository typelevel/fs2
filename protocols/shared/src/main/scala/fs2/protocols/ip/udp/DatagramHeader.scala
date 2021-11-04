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

import scodec.Codec
import scodec.codecs._
import fs2.interop.scodec._
import com.comcast.ip4s.Port

case class DatagramHeader(sourcePort: Port, destinationPort: Port, length: Int, checksum: Int)

object DatagramHeader {
  // format: off
  implicit val codec: Codec[DatagramHeader] = {
    ("src_port" | Ip4sCodecs.port) ::
    ("dst_port" | Ip4sCodecs.port) ::
    ("length"   | uint16         ) ::
    ("checksum" | uint16         )
  }.as[DatagramHeader]
  // format: on

  def sdecoder(protocol: Int): StreamDecoder[DatagramHeader] =
    if (protocol == 17) StreamDecoder.once(codec)
    else StreamDecoder.empty
}
