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

package fs2.protocols.pcap

import scodec.Codec
import scodec.bits.ByteOrdering

/** Describes the link layer type in a PCAP capture.
  * @see http://www.tcpdump.org/linktypes.html
  */
sealed trait LinkType

/** Companion for [[LinkType]]. */
object LinkType {
  case object Null extends LinkType
  case object Ethernet extends LinkType
  case object Raw extends LinkType
  case object IPv4 extends LinkType
  case object IPv6 extends LinkType
  case object MPEG2TS extends LinkType
  case class Unknown(value: Long) extends LinkType

  def toLong(lt: LinkType): Long = lt match {
    case Null           => 0
    case Ethernet       => 1
    case Raw            => 101
    case IPv4           => 228
    case IPv6           => 229
    case MPEG2TS        => 243
    case Unknown(value) => value
  }

  def fromLong(l: Long): LinkType = l match {
    case 0     => Null
    case 1     => Ethernet
    case 101   => Raw
    case 228   => IPv4
    case 229   => IPv6
    case 243   => MPEG2TS
    case other => Unknown(other)
  }

  implicit def codec(implicit bo: ByteOrdering): Codec[LinkType] =
    guint32.xmap[LinkType](fromLong, toLong)
}
