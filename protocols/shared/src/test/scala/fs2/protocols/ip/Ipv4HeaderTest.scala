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
package protocols.ip

import com.comcast.ip4s.Ipv4Address
import scodec.{Codec, DecodeResult}
import scodec.bits._
import scodec.bits.BitVector.fromInt
import scodec.bits.ByteOrdering.BigEndian

class Ipv4HeaderTest extends Fs2Suite {
  private val version = bin"0100"
  private val dscef = bin"000000"
  private val ecn = bin"00"
  private val id = hex"6681".bits
  private val flags = bin"000"
  private val offset = BitVector.high(13)
  private val ttl = hex"64".bits
  private val protocol = hex"11".bits
  private val checksum = hex"fa31".bits
  private val src = hex"0ae081b2".bits
  private val dst = hex"0ae081ab".bits
  private val dataLength = 80
  private val data = BitVector.high(dataLength)

  test("decode IPv4 packet without options") {
    val headerLength = bin"0101" // 5 32-bit words

    val packetData =
      version ++ headerLength ++ dscef ++ ecn ++ fromInt(dataLength + 20, size = 16) ++ id ++
        flags ++ offset ++ ttl ++ protocol ++ checksum ++ src ++ dst ++ data

    val res = Codec[Ipv4Header].decode(packetData).require
    assertHeader(res, BitVector.empty)
  }

  test("decode IPv4 packet with options") {
    val headerLength = bin"0110" // 6 32-bit words
    val options = fromInt(1234)

    val packetData =
      version ++ headerLength ++ dscef ++ ecn ++ fromInt(dataLength + 24, size = 16) ++ id ++
        flags ++ offset ++ ttl ++ protocol ++ checksum ++ src ++ dst ++ options ++ data

    val res = Codec[Ipv4Header].decode(packetData).require
    assertHeader(res, options)
  }

  test("encode IPv4 header with options") {
    val headerLength = bin"0110" // 6 32-bit words
    val options = fromInt(1234)

    val rawData =
      version ++ headerLength ++ BitVector.low(8) ++ fromInt(dataLength + 24, size = 16) ++ id ++
        flags ++ BitVector.low(13) ++ ttl ++ protocol ++ BitVector.low(16) ++ src ++ dst ++ options

    val checksum = Checksum.checksum(rawData)

    val expectedData = rawData.patch(80, checksum)

    val header = Ipv4Header(
      dataLength = dataLength,
      id = id.toInt(signed = false),
      ttl = ttl.toInt(signed = false),
      protocol = protocol.toInt(signed = false),
      sourceIp = Ipv4Address.fromBytes(src.toByteArray).get,
      destinationIp = Ipv4Address.fromBytes(dst.toByteArray).get,
      options = options
    )

    val headerData = Codec[Ipv4Header].encode(header).require
    assertEquals(headerData.toBin, expectedData.toBin)
  }

  private def assertHeader(res: DecodeResult[Ipv4Header], options: BitVector): Unit = {
    val header = res.value
    assertEquals(header.ttl, ttl.toInt(signed = false, BigEndian))
    assertEquals(header.id, id.toInt(signed = false, BigEndian))
    assertEquals(header.protocol, protocol.toInt(signed = false, BigEndian))
    assertEquals(header.sourceIp.toLong, src.toLong(signed = false))
    assertEquals(header.destinationIp.toLong, dst.toLong(signed = false))
    assertEquals(header.options, options)
    assertEquals(header.dataLength, dataLength)
    assertEquals(res.remainder, data)
  }
}
