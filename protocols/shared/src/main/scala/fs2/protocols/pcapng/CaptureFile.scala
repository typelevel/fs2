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

package fs2.protocols.pcapng

import cats.effect.MonadCancelThrow
import fs2.interop.scodec.StreamDecoder
import fs2.protocols.pcap.LinkType
import fs2.timeseries.TimeStamped
import fs2.{Pipe, Pull, Stream}
import scodec.bits.ByteVector

object CaptureFile {

  val streamDecoder: StreamDecoder[BodyBlock] =
    StreamDecoder.once(SectionHeaderBlock.codec).flatMap { shb =>
      StreamDecoder.many(BodyBlock.decoder(shb.ordering))
    }

  def parse[F[_]: MonadCancelThrow, A](
      f: (LinkType, ByteVector) => Option[A]
  ): Pipe[F, Byte, TimeStamped[A]] = { bytes =>
    def go(
        idbs: Vector[InterfaceDescriptionBlock],
        blocks: Stream[F, BodyBlock],
    ): Pull[F, TimeStamped[A], Unit] =
      blocks.pull.uncons1.flatMap {
        case Some((idb: InterfaceDescriptionBlock, tail)) =>
          go(idbs :+ idb, tail)
        case Some((epb: EnhancedPacketBlock, tail)) =>
          val idb = idbs(epb.interfaceId.toInt)
          val ts = ((epb.timestampHigh << 32) | epb.timestampLow) * idb.if_tsresol
          val timeStamped = f(idb.linkType, epb.packetData).map(TimeStamped(ts, _))
          Pull.outputOption1(timeStamped) >> go(idbs, tail)
        case Some((_, tail)) => go(idbs, tail)
        case None            => Pull.done
      }

    val blocks = bytes.through(streamDecoder.toPipeByte)
    blocks.through(go(Vector.empty, _).stream)
  }
}
