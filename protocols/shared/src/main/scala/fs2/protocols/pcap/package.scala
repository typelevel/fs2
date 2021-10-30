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

import scodec.bits.ByteOrdering
import scodec.Codec
import scodec.codecs._

/** Protocol that describes libpcap files.
  *
  * @see
  *   http://wiki.wireshark.org/Development/LibpcapFileFormat
  */
package object pcap {

  def gint16(implicit ordering: ByteOrdering): Codec[Int] = endiannessDependent(int16, int16L)
  def guint16(implicit ordering: ByteOrdering): Codec[Int] = endiannessDependent(uint16, uint16L)
  def gint32(implicit ordering: ByteOrdering): Codec[Int] = endiannessDependent(int32, int32L)
  def guint32(implicit ordering: ByteOrdering): Codec[Long] = endiannessDependent(uint32, uint32L)
}
