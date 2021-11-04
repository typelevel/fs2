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

import scodec.bits._
import scodec.{Codec, SizeBound}
import scodec.codecs._
import scodec.compat._
import fs2.interop.scodec._
import fs2.protocols.ethernet.{EtherType, EthernetFrameHeader}
import com.comcast.ip4s.Ipv4Address

/** IPv4 header. */
case class Ipv4Header(
    dataLength: Int,
    id: Int,
    ttl: Int,
    protocol: Int,
    sourceIp: Ipv4Address,
    destinationIp: Ipv4Address,
    options: BitVector
)

object Ipv4Header {

  implicit val codec: Codec[Ipv4Header] = {
    // format: off
    val componentCodec = {
      // Word 1 --------------------------------
      ("version"         | constant(bin"0100")) ::
      ("ihl"             | uint4              ).flatPrepend(ihl =>
      ("dscp"            | ignore(6)          ) ::
      ("ecn"             | ignore(2)          ) ::
      ("total_length"    | uint16             ) ::
      // Word 2 --------------------------------
      ("id"              | uint16             ) ::
      ("flags"           | ignore(3)          ) ::
      ("fragment_offset" | ignore(13)         ) ::
      // Word 3 --------------------------------
      ("ttl"             | uint8              ) ::
      ("proto"           | uint8              ) ::
      ("checksum"        | bits(16)           ) ::
      // Word 4 --------------------------------
      ("src_ip"          | Ip4sCodecs.ipv4    ) ::
      // Word 5 --------------------------------
      ("dest_ip"         | Ip4sCodecs.ipv4    ) ::
      // Word 6 --------------------------------
      ("options"         | bits((32 * (ihl - 5)).toLong)))
    }.dropUnits
    // format: on

    new Codec[Ipv4Header] {
      def sizeBound = SizeBound.atLeast(160)

      def encode(header: Ipv4Header) = {
        val optionWords = (header.options.size.toInt + 31) / 32
        val options = header.options.padRight((optionWords * 32).toLong)
        val totalLength = header.dataLength + 20 + (optionWords * 4)
        for {
          encoded <- componentCodec.encode(
            (5 + optionWords) *: totalLength *: header.id *: header.ttl *: header.protocol *: BitVector
              .low(16) *: header.sourceIp *: header.destinationIp *: options *: EmptyTuple
          )
          chksum = Checksum.checksum(encoded)
        } yield encoded.patch(80L, chksum)
      }

      def decode(bits: BitVector) =
        componentCodec.decode(bits).map {
          _.map { t =>
            Ipv4Header(t(1) - (t(0) * 4), t(2), t(3), t(4), t(6), t(7), t(8))
          }
        }
    }
  }

  def sdecoder(ethernetHeader: EthernetFrameHeader): StreamDecoder[Ipv4Header] =
    if (ethernetHeader.ethertype == Some(EtherType.IPv4)) StreamDecoder.once(codec)
    else StreamDecoder.empty
}
