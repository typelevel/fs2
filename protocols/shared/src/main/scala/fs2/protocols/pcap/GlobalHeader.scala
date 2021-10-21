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

import scodec.Err
import scodec.bits.{BitVector, ByteOrdering}
import scodec.{Attempt, Codec, DecodeResult, SizeBound}
import scodec.codecs._

case class GlobalHeader(
    ordering: ByteOrdering,
    versionMajor: Int,
    versionMinor: Int,
    thiszone: Int,
    sigfigs: Long,
    snaplen: Long,
    network: LinkType
)

object GlobalHeader {
  private val MagicNumber = 0xa1b2c3d4L
  private val MagicNumberRev = 0xd4c3b2a1L

  private val byteOrdering: Codec[ByteOrdering] = new Codec[ByteOrdering] {
    def sizeBound = SizeBound.exact(32)

    def encode(bo: ByteOrdering) =
      endiannessDependent(uint32, uint32L)(bo).encode(MagicNumber)

    def decode(buf: BitVector) =
      uint32.decode(buf).flatMap {
        case DecodeResult(MagicNumber, rest) =>
          Attempt.successful(DecodeResult(ByteOrdering.BigEndian, rest))
        case DecodeResult(MagicNumberRev, rest) =>
          Attempt.successful(DecodeResult(ByteOrdering.LittleEndian, rest))
        case DecodeResult(other, _) =>
          Attempt.failure(
            Err(s"unable to detect byte ordering due to unrecognized magic number $other")
          )
      }

    override def toString = "byteOrdering"
  }

  // format: off
  implicit val codec: Codec[GlobalHeader] = "global-header" | {
    ("magic_number"  | byteOrdering    ).flatPrepend { implicit ordering =>
    ("version_major" | guint16         ) ::
    ("version_minor" | guint16         ) ::
    ("thiszone"      | gint32          ) ::
    ("sigfigs"       | guint32         ) ::
    ("snaplen"       | guint32         ) ::
    ("network"       | LinkType.codec  )
  }}.as[GlobalHeader]
  // format: on
}
