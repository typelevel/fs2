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
package transport

import scodec.{Attempt, Codec, DecodeResult, SizeBound}
import scodec.bits.BitVector
import scodec.codecs._
import scodec.compat._

/** Partial modelling of the adaptation field.
  * The field extension, if present, is ignored upon decoding.
  */
case class AdaptationField(
    flags: Option[AdaptationFieldFlags],
    pcr: Option[Clock27MHz],
    opcr: Option[Clock27MHz],
    spliceCountdown: Option[Int],
    transportPrivateData: Option[BitVector]
)

object AdaptationField {

  final val Empty: AdaptationField = AdaptationField(None, None, None, None, None)

  implicit val codec: Codec[AdaptationField] = new Codec[AdaptationField] {
    private case class NonEmptyAF(
        flags: AdaptationFieldFlags,
        pcr: Option[Clock27MHz],
        opcr: Option[Clock27MHz],
        spliceCountdown: Option[Int],
        transportPrivateData: Option[BitVector]
    ) {
      def asAF: AdaptationField =
        AdaptationField(Some(flags), pcr, opcr, spliceCountdown, transportPrivateData)
    }

    private val pcrCodec: Codec[Clock27MHz] =
      ((ulong(33) <~ ignore(6)) :: uint(9)).xmap[Clock27MHz](
        { case base *: ext *: EmptyTuple =>
          Clock27MHz(base * 300 + ext)
        },
        { clock =>
          val value = clock.value
          val base = value / 300
          val ext = (value % 300).toInt
          base *: ext *: EmptyTuple
        }
      )
    private val transportPrivateData: Codec[BitVector] = variableSizeBits(uint8, bits)
    private val nonEmptyAFCodec: Codec[NonEmptyAF] = "adaptation_field" |
      variableSizeBytes(
        uint8,
        ("adaptation_flags" | Codec[AdaptationFieldFlags]).flatPrepend { flags =>
          ("pcr" | conditional(flags.pcrFlag, pcrCodec)) ::
            ("opcr" | conditional(flags.opcrFlag, pcrCodec)) ::
            ("splice_countdown" | conditional(flags.splicingPointFlag, int8)) ::
            ("transport_private_data" | conditional(
              flags.transportPrivateDataFlag,
              transportPrivateData
            ))
        }
      )
        .as[NonEmptyAF]

    def sizeBound: SizeBound = SizeBound.unknown

    def encode(af: AdaptationField): Attempt[BitVector] = af.flags.fold(uint8.encode(0)) { flags =>
      nonEmptyAFCodec.encode(
        NonEmptyAF(flags, af.pcr, af.opcr, af.spliceCountdown, af.transportPrivateData)
      )
    }

    def decode(bv: BitVector): Attempt[DecodeResult[AdaptationField]] =
      uint8.decode(bv).flatMap { size =>
        if (size.value > 0) nonEmptyAFCodec.decode(bv).map(_.map(_.asAF))
        else Attempt.successful(DecodeResult(Empty, size.remainder))
      }
  }
}
