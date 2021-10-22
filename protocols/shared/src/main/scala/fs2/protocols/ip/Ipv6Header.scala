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

import scodec._
import scodec.bits._
import scodec.codecs._
import fs2.interop.scodec._
import fs2.protocols.ethernet.{EthernetFrameHeader, EtherType}
import com.comcast.ip4s.Ipv6Address

/** Simplified model of an IPv6 header -- extension headers are not directly supported. */
case class Ipv6Header(
  trafficClass: Int,
  flowLabel: Int,
  payloadLength: Int,
  protocol: Int,
  hopLimit: Int,
  sourceIp: Ipv6Address,
  destinationIp: Ipv6Address
)

object Ipv6Header {
  implicit val codec: Codec[Ipv6Header] = {
    ("version"             | constant(bin"0110")) ~>
    ("traffic_class"       | uint8) ::
    ("flow_label"          | uint(20)) ::
    ("payload_length"      | uint(16)) ::
    ("next_header"         | uint8) ::
    ("hop_limit"           | uint8) ::
    ("source_address"      | Ip4sCodecs.ipv6) ::
    ("destination_address" | Ip4sCodecs.ipv6)
  }.as[Ipv6Header]

  def sdecoder(ethernetHeader: EthernetFrameHeader): StreamDecoder[Ipv6Header] =
    if (ethernetHeader.ethertype == Some(EtherType.IPv6)) StreamDecoder.once(codec)
    else StreamDecoder.empty
}
