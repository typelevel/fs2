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
import scodec.Codec
import scodec.codecs._

sealed abstract class PesScramblingControl
object PesScramblingControl {
  object NotScrambled extends PesScramblingControl
  object UserDefined1 extends PesScramblingControl
  object UserDefined2 extends PesScramblingControl
  object UserDefined3 extends PesScramblingControl

  implicit val codec: Codec[PesScramblingControl] = mappedEnum(
    bits(2),
    NotScrambled -> bin"00",
    UserDefined1 -> bin"01",
    UserDefined2 -> bin"10",
    UserDefined3 -> bin"11"
  )
}

case class PesPacketHeader(
    pesScramblingControl: PesScramblingControl,
    pesPriority: Boolean,
    dataAlignmentIndicator: Boolean,
    copyright: Boolean,
    originalOrCopy: Boolean,
    flags: PesPacketHeader.Flags, // TODO
    pts: Option[Long],
    dts: Option[Long],
    escr: Option[Long],
    esRate: Option[Int],
    dsmTrickMode: Option[BitVector],
    additionalCopyInfo: Option[BitVector],
    pesCrc: Option[Int],
    extension: Option[PesPacketHeader.Extension]
)

object PesPacketHeader {

  case class Flags(
      ptsFlag: Boolean,
      dtsFlag: Boolean,
      escrFlag: Boolean,
      esRateFlag: Boolean,
      dsmTrickModeFlag: Boolean,
      additionalCopyInfoFlag: Boolean,
      pesCrcFlag: Boolean,
      pesExtensionFlag: Boolean
  )

  object Flags {
    implicit val codec: Codec[Flags] = {
      ("pts_dts_flags[0]" | bool) ::
        ("pts_dts_flags[1]" | bool) ::
        ("escr_flag" | bool) ::
        ("es_rate_flag" | bool) ::
        ("dsm_trick_mode_flag" | bool) ::
        ("additional_copy_info_flag" | bool) ::
        ("pes_crc_flag" | bool) ::
        ("pes_extension_flag" | bool)
    }.as[Flags]
  }

  case class ExtensionFlags(
      pesPrivateDataFlag: Boolean,
      packHeaderFieldFlag: Boolean,
      programPacketSequenceCounterFlag: Boolean,
      pstdBufferFlag: Boolean,
      pesExtensionFlag2: Boolean
  )
  object ExtensionFlags {
    implicit val codec: Codec[ExtensionFlags] = {
      ("pes_private_data_flag" | bool) ::
        ("pack_header_field_flag" | bool) ::
        ("program_packet_sequence_counter_flag" | bool) ::
        ("P-STD_buffer_flag" | bool) ::
        reserved(3) ::
        ("pes_extension_flag_2" | bool)
    }.dropUnits.as[ExtensionFlags]
  }

  case class ProgramPacketSequenceCounter(counter: Int, mpeg1: Boolean, originalStuffLength: Int)
  object ProgramPacketSequenceCounter {
    implicit val codec: Codec[ProgramPacketSequenceCounter] =
      (marker :: uint(7) :: marker :: bool :: uint(6)).dropUnits.as[ProgramPacketSequenceCounter]
  }

  case class PStdBuffer(scale: Boolean, size: Int)
  object PStdBuffer {
    implicit val codec: Codec[PStdBuffer] =
      (constant(bin"01") ~> bool :: uint(13)).as[PStdBuffer]
  }

  case class Extension(
      flags: ExtensionFlags, // TODO
      pesPrivateData: Option[BitVector],
      packHeaderField: Option[BitVector],
      programPacketSequenceCounter: Option[ProgramPacketSequenceCounter],
      pstdBuffer: Option[PStdBuffer],
      extension: Option[BitVector]
  )
  object Extension {
    implicit val codec: Codec[Extension] =
      Codec[ExtensionFlags]
        .flatPrepend { flags =>
          ("pes_private_data" | conditional(flags.pesPrivateDataFlag, bits(128))) ::
            ("pack_header_field" | conditional(
              flags.packHeaderFieldFlag,
              variableSizeBytes(uint8, bits)
            )) ::
            ("program_packet_sequence_counter" | conditional(
              flags.programPacketSequenceCounterFlag,
              Codec[ProgramPacketSequenceCounter]
            )) ::
            ("P-STD_buffer" | conditional(flags.pstdBufferFlag, Codec[PStdBuffer])) ::
            ("pes_extension_2" | conditional(
              flags.pesExtensionFlag2,
              marker ~> variableSizeBytes(uint(7), bits)
            ))
        }
        .as[Extension]
  }

  private val marker: Codec[Unit] = constantLenient(bin"1")

  private def tsCodec(prefix: BitVector) =
    (constant(prefix) :: bits(3) :: marker :: bits(15) :: marker :: bits(15) :: marker).dropUnits
      .xmap[Long](
        { case (a, b, c) => (a ++ b ++ c).toLong() },
        l => {
          val b = BitVector.fromLong(l).drop(31)
          (b.take(3), b.drop(3).take(15), b.drop(18))
        }
      )

  private val escrCodec: Codec[Long] =
    (ignore(2) :: bits(3) :: marker :: bits(15) :: marker :: bits(15) :: marker :: uint(
      9
    ) :: marker).dropUnits.xmap[Long](
      { case (a, b, c, ext) =>
        val base = (a ++ b ++ c).toLong()
        base * 300 + ext
      },
      l => {
        val base = (l / 300) % (2L << 32)
        val b = BitVector.fromLong(base).drop(31)
        val ext = (l % 300).toInt
        (b.take(3), b.drop(3).take(15), b.drop(18), ext)
      }
    )

  implicit val codec: Codec[PesPacketHeader] = {
    constant(bin"10") ~>
      ("pes_scrambling_control" | PesScramblingControl.codec) ::
      ("pes_priority" | bool) ::
      ("data_alignment_indicator" | bool) ::
      ("copyright" | bool) ::
      ("original_or_copy" | bool) ::
      ("flags" | Codec[Flags]).flatPrepend { flags =>
        variableSizeBytes(
          uint8,
          ("pts" | conditional(flags.ptsFlag, tsCodec(bin"0011"))) ::
            ("dts" | conditional(flags.dtsFlag, tsCodec(bin"0001"))) ::
            ("escr" | conditional(flags.escrFlag, escrCodec)) ::
            ("es_rate" | conditional(flags.esRateFlag, ignore(1) ~> uint(22) <~ ignore(1))) ::
            ("dsm_trick_mode" | conditional(flags.dsmTrickModeFlag, bits(8))) ::
            ("additional_copy_info" | conditional(
              flags.additionalCopyInfoFlag,
              ignore(1) ~> bits(7)
            )) ::
            ("pes_crc" | conditional(flags.pesCrcFlag, uint16)) ::
            ("extension" | conditional(flags.pesExtensionFlag, Codec[Extension]))
        )
      } // .removeElem[Flags](Generic[Flags].from(optionalFields.map(_.isDefined)))
  }.withContext("pes_packet_header").as[PesPacketHeader]
}
