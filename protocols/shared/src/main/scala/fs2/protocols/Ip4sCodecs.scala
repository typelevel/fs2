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

import scodec.Codec
import scodec.bits._
import scodec.codecs._
import com.comcast.ip4s._

object Ip4sCodecs {
  val ipv4: Codec[Ipv4Address] =
    bytes(4).xmapc(b => Ipv4Address.fromBytes(b.toArray).get)(a => ByteVector.view(a.toBytes))

  val ipv6: Codec[Ipv6Address] =
    bytes(16).xmapc(b => Ipv6Address.fromBytes(b.toArray).get)(a => ByteVector.view(a.toBytes))

  val macAddress: Codec[MacAddress] =
    bytes(6).xmapc(b => MacAddress.fromBytes(b.toArray).get)(m => ByteVector.view(m.toBytes))

  val port: Codec[Port] = uint16.xmapc(p => Port.fromInt(p).get)(_.value)
}
