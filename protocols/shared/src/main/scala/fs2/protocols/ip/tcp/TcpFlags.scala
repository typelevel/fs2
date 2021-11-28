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

case class TcpFlags(
    cwr: Boolean,
    ecn: Boolean,
    urg: Boolean,
    ack: Boolean,
    psh: Boolean,
    rst: Boolean,
    syn: Boolean,
    fin: Boolean
)
object TcpFlags {
  // format: off
  implicit val codec: Codec[TcpFlags] = {
    ("cwr" | bool(1)) ::
    ("ecn" | bool(1)) ::
    ("urg" | bool(1)) ::
    ("ack" | bool(1)) ::
    ("psh" | bool(1)) ::
    ("rst" | bool(1)) ::
    ("syn" | bool(1)) ::
    ("fin" | bool(1))
  }.as[TcpFlags]
  // format: on
}
