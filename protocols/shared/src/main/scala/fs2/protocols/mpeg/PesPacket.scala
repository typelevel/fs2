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

package fs2.protocols.mpeg

import scodec.bits._
import scodec.{ Attempt, Decoder, DecodeResult, Err }

trait PesPacket

object PesPacket {

  case class WithHeader(streamId: Int, header: PesPacketHeader, data: BitVector) extends PesPacket
  case class WithoutHeader(streamId: Int, data: BitVector) extends PesPacket
  case object Padding extends PesPacket

  def decode(prefix: PesPacketHeaderPrefix, buffer: BitVector): Attempt[DecodeResult[PesPacket]] =
    decoder(prefix).decode(buffer)

  def decoder(prefix: PesPacketHeaderPrefix): Decoder[PesPacket] = Decoder { buffer =>
    val id = prefix.streamId
    import PesStreamId._
    if (id != ProgramStreamMap &&
        id != PaddingStream &&
        id != PrivateStream2 &&
        id != ECM &&
        id != EMM &&
        id != ProgramStreamDirectory &&
        id != DSMCC &&
        id != `ITU-T Rec. H.222.1 type E`) {
      PesPacketHeader.codec.decode(buffer) match {
        case Attempt.Successful(DecodeResult(header, rest)) =>
          decodeWithHeader(prefix, header, rest)
        case f @ Attempt.Failure(_) => f
      }
    } else if (
      id == ProgramStreamMap ||
      id == PrivateStream2 ||
      id == ECM ||
      id == EMM |
      id == ProgramStreamDirectory ||
      id == DSMCC ||
      id == `ITU-T Rec. H.222.1 type E`) {
      Attempt.successful(DecodeResult(WithoutHeader(id, buffer), BitVector.empty))
    } else if (id == PaddingStream) {
      Attempt.successful(DecodeResult(Padding, BitVector.empty))
    } else {
      Attempt.failure(Err(s"Unknown PES stream id: $id"))
    }
  }

  def decodeWithHeader(prefix: PesPacketHeaderPrefix, header: PesPacketHeader, data: BitVector): Attempt[DecodeResult[PesPacket]] = {
    Attempt.successful(DecodeResult(WithHeader(prefix.streamId, header, data), BitVector.empty))
  }
}
