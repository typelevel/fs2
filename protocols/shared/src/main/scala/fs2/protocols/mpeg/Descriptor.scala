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
package psi

import scodec._
import scodec.bits._
import scodec.codecs._

trait KnownDescriptor

sealed trait TransportStreamDescriptor extends KnownDescriptor
sealed trait ProgramStreamDescriptor extends KnownDescriptor

case class Mpeg1Only(profileAndLevelIndication: Int, chromaFormat: Int, frameRateExtensionFlag: Boolean)
object Mpeg1Only {
  implicit val codec: Codec[Mpeg1Only] = {
    ("profile_and_level_indication" | uint8) ::
    ("chroma_format" | uint(2)) ::
    ("frame_rate_extension_flag" | bool) ::
    ("reserved" | reserved(5))
  }.dropUnits.as[Mpeg1Only]
}
case class VideoStreamDescriptor(
  multipleFrameRateFlag: Boolean,
  frameRateCode: Int,
  mpeg1OnlyFlag: Boolean,
  constrainedParameter: Boolean,
  stillPictureFlag: Boolean,
  mpeg1Only: Option[Mpeg1Only]) extends TransportStreamDescriptor with ProgramStreamDescriptor

object VideoStreamDescriptor {
  val codec: Codec[VideoStreamDescriptor] = {
    ("multiple_frame_rate_flag" | bool) ::
    ("frame_rate_code" | uint4) ::
    (("MPEG_1_only_flag" | bool).flatPrepend { mpeg1Only =>
      ("constrained_parameter" | bool) ::
      ("still_picture_flag" | bool) ::
      ("MPEG_1_only_attributes" | conditional(mpeg1Only, Codec[Mpeg1Only]))
    })
  }.as[VideoStreamDescriptor]
}

case class AudioStreamDescriptor(freeFormatFlag: Boolean, id: Boolean, layer: Int, variableRateAudioIndicator: Boolean) extends TransportStreamDescriptor with ProgramStreamDescriptor
object AudioStreamDescriptor {
  val codec: Codec[AudioStreamDescriptor] = {
    ("free_format_flag" | bool) ::
    ("ID" | bool) ::
    ("layer" | uint(2)) ::
    ("variable_rate_audio_indicator" | bool) ::
    ("reserved" | reserved(3))
  }.dropUnits.as[AudioStreamDescriptor]
}

sealed trait HierarchyType
object HierarchyType {
  case object SpatialScalability extends HierarchyType
  case object SnrScalability extends HierarchyType
  case object TemporalScalability extends HierarchyType
  case object DataPartitioning extends HierarchyType
  case object ExtensionBitstream extends HierarchyType
  case object PrivateStream extends HierarchyType
  case object MultiViewProfile extends HierarchyType
  case class Reserved(value: Int) extends HierarchyType
  case object BaseLayer extends HierarchyType

  implicit val codec: Codec[HierarchyType] = {
    val m = discriminated[HierarchyType].by(uint4)
      .typecase(0, provide(Reserved(0)))
      .typecase(1, provide(SpatialScalability))
      .typecase(2, provide(SnrScalability))
      .typecase(3, provide(TemporalScalability))
      .typecase(4, provide(DataPartitioning))
      .typecase(5, provide(ExtensionBitstream))
      .typecase(6, provide(PrivateStream))
      .typecase(7, provide(MultiViewProfile))
      .typecase(15, provide(BaseLayer))
      (8 to 14).foldLeft(m) { (acc, x) => acc.subcaseP(x)({ case Reserved(y) if x == y => Reserved(y) })(provide(Reserved(x))) }
  }
}
case class HierarchyDescriptor(hierarchyType: HierarchyType, hierarchyLayerIndex: Int, hierarchyEmbeddedLayerIndex: Int, hierarchyChannel: Int) extends TransportStreamDescriptor with ProgramStreamDescriptor
object HierarchyDescriptor {
  val codec: Codec[HierarchyDescriptor] = {
    ("reserved" | reserved(4)) ::
    ("hierarchy_type" | Codec[HierarchyType]) ::
    ("reserved" | reserved(2)) ::
    ("hierarchy_layer_index" | uint(6)) ::
    ("reserved" | reserved(2)) ::
    ("hierarchy_embedded_layer_index" | uint(6)) ::
    ("reserved" | reserved(2)) ::
    ("hierarchy_channel" | uint(6))
  }.dropUnits.as[HierarchyDescriptor]
}

case class RegistrationDescriptor(formatIdentifier: ByteVector, additionalIdentificationInfo: ByteVector) extends TransportStreamDescriptor with ProgramStreamDescriptor
object RegistrationDescriptor {
  val codec: Codec[RegistrationDescriptor] = {
    (("format_identifier" | bytes(4)) :: bytes)
  }.as[RegistrationDescriptor]
}

sealed trait AlignmentType
object AlignmentType {
  case object SliceOrVideoAccessUnit extends AlignmentType
  case object VideoAccessUnit extends AlignmentType
  case object GopOrSeq extends AlignmentType
  case object Seq extends AlignmentType
  case class Reserved(value: Int) extends AlignmentType
  implicit val codec: Codec[AlignmentType] = {
    val m = discriminated[AlignmentType].by(uint8)
      .typecase(0, provide(Reserved(0)))
      .typecase(1, provide(SliceOrVideoAccessUnit))
      .typecase(2, provide(VideoAccessUnit))
      .typecase(3, provide(GopOrSeq))
      .typecase(4, provide(Seq))
      (5 to 255).foldLeft(m) { (acc, x) => acc.subcaseP(x)({ case Reserved(y) if x == y => Reserved(y) })(provide(Reserved(x))) }
  }
}
case class DataStreamAlignmentDescriptor(alignmentType: AlignmentType) extends TransportStreamDescriptor with ProgramStreamDescriptor
object DataStreamAlignmentDescriptor {
  val codec: Codec[DataStreamAlignmentDescriptor] = {
    ("alignment_type" | Codec[AlignmentType])
  }.as[DataStreamAlignmentDescriptor]
}

case class TargetBackgroundGridDescriptor(horizontalSize: Int, verticalSize: Int, aspectRatioInformation: Int) extends TransportStreamDescriptor with ProgramStreamDescriptor
object TargetBackgroundGridDescriptor {
  val codec: Codec[TargetBackgroundGridDescriptor] = {
    ("horizontal_size" | uint(14)) ::
    ("vertical_size" | uint(14)) ::
    ("aspect_ratio_information" | uint4)
  }.as[TargetBackgroundGridDescriptor]
}

case class VideoWindowDescriptor(horizontalOffset: Int, verticalOffset: Int, windowPriority: Int) extends TransportStreamDescriptor with ProgramStreamDescriptor
object VideoWindowDescriptor {
  val codec: Codec[VideoWindowDescriptor] = {
    ("horizontal_offset" | uint(14)) ::
    ("vertical_offset" | uint(14)) ::
    ("window_priority" | uint4)
  }.as[VideoWindowDescriptor]
}

case class CADescriptor(caSystemId: Int, caPid: Pid, privateData: ByteVector) extends TransportStreamDescriptor with ProgramStreamDescriptor
object CADescriptor {
  val codec: Codec[CADescriptor] = {
    ("CA_system_id" | uint16) :: reserved(3) :: ("CA_PID" | Codec[Pid]) :: bytes
  }.as[CADescriptor]
}

sealed trait AudioType
object AudioType {
  case object Undefined extends AudioType
  case object CleanEffects extends AudioType
  case object HearingImpaired extends AudioType
  case object VisualImpairedCommentary extends AudioType
  case class Reserved(value: Int) extends AudioType

  implicit val codec: Codec[AudioType] = {
    val m = discriminated[AudioType].by(uint8)
      .typecase(0, provide(Undefined))
      .typecase(1, provide(CleanEffects))
      .typecase(2, provide(HearingImpaired))
      .typecase(3, provide(VisualImpairedCommentary))
    (4 to 255).foldLeft(m) { (acc, x) => acc.subcaseP(x)({ case Reserved(y) if x == y => Reserved(y) })(provide(Reserved(x))) }
  }
}

case class LanguageField(iso639LanguageCode: String, audioType: AudioType)
object LanguageField {
  implicit val codec: Codec[LanguageField] = {
    ("ISO_639_language_code" | fixedSizeBytes(3, ascii)) ::
    ("audio_type" | Codec[AudioType])
  }.as[LanguageField]
}

case class Iso639LanguageDescriptor(languageFields: Vector[LanguageField]) extends TransportStreamDescriptor with ProgramStreamDescriptor
object Iso639LanguageDescriptor {
  val codec: Codec[Iso639LanguageDescriptor] = {
    vector(Codec[LanguageField])
  }.as[Iso639LanguageDescriptor]
}


case class SystemClockDescriptor(externalClockReferenceIndicator: Boolean, clockAccuracyInteger: Int, clockAccuracyExponent: Int) extends TransportStreamDescriptor with ProgramStreamDescriptor
object SystemClockDescriptor {
  val codec: Codec[SystemClockDescriptor] = {
    ("external_clock_reference_indicator" | bool) ::
    ("reserved" | reserved(1)) ::
    ("clock_accuracy_integer" | uint(6)) ::
    ("clock_accuracy_exponent" | uint(3)) ::
    ("reserved" | reserved(5))
  }.dropUnits.as[SystemClockDescriptor]
}

case class MultiplexBufferUtilizationDescriptor(boundValidFlag: Boolean, ltwOffsetLowerBound: Int, ltwOffsetUpperBound: Int) extends TransportStreamDescriptor with ProgramStreamDescriptor
object MultiplexBufferUtilizationDescriptor {
  val codec: Codec[MultiplexBufferUtilizationDescriptor] = {
    ("bound_valid_flag" | bool) ::
    ("LTW_offset_lower_bound" | uint(15)) ::
    ("reserved" | reserved(1)) ::
    ("LTW_offset_upper_bound" | uint(15))
  }.dropUnits.as[MultiplexBufferUtilizationDescriptor]
}

case class CopyrightDescriptor(copyrightIdentifier: ByteVector, additionalCopyrightInfo: ByteVector) extends TransportStreamDescriptor with ProgramStreamDescriptor
object CopyrightDescriptor {
  val codec: Codec[CopyrightDescriptor] = {
    bytes(4) :: bytes
  }.as[CopyrightDescriptor]
}

case class MaximumBitrateDescriptor(maximumBitrate: Int) extends TransportStreamDescriptor
object MaximumBitrateDescriptor {
  val codec: Codec[MaximumBitrateDescriptor] = {
    ("reserved" | reserved(2)) ::
    ("maximum_bitrate" | uint(22))
  }.dropUnits.as[MaximumBitrateDescriptor]
}

case class PrivateDataIndicatorDescriptor(privateDataIndicator: ByteVector) extends TransportStreamDescriptor with ProgramStreamDescriptor
object PrivateDataIndicatorDescriptor {
  val codec: Codec[PrivateDataIndicatorDescriptor] = {
    ("private_data_indicator" | bytes(4))
  }.as[PrivateDataIndicatorDescriptor]
}

case class SmoothingBufferDescriptor(sbLeakRate: Int, sbSize: Int) extends TransportStreamDescriptor with ProgramStreamDescriptor
object SmoothingBufferDescriptor {
  val codec: Codec[SmoothingBufferDescriptor] = {
    ("reserved" | reserved(2)) ::
    ("sb_leak_rate" | uint(22)) ::
    ("reserved" | reserved(2)) ::
    ("sb_size" | uint(22))
  }.dropUnits.as[SmoothingBufferDescriptor]
}

case class StdDescriptor(leakValidFlag: Boolean) extends TransportStreamDescriptor
object StdDescriptor {
  val codec: Codec[StdDescriptor] = {
    ("reserved" | reserved(7)) ::
    ("leak_valid_flag" | bool)
  }.dropUnits.as[StdDescriptor]
}

case class IbpDescriptor(closedGopFlag: Boolean, identicalGopFlag: Boolean, maxGopLength: Int) extends TransportStreamDescriptor with ProgramStreamDescriptor
object IbpDescriptor {
  val codec: Codec[IbpDescriptor] = {
    ("closed_gop_flag" | bool) ::
    ("identical_gop_flag" | bool) ::
    ("max_gop_length" | uint(14))
  }.as[IbpDescriptor]
}

case class Mpeg4VideoDescriptor(mpeg4VisualProfileAndLevel: Byte) extends TransportStreamDescriptor with ProgramStreamDescriptor
object Mpeg4VideoDescriptor {
  val codec: Codec[Mpeg4VideoDescriptor] = {
    ("MPEG-4_visual_profile_and_level" | byte)
  }.as[Mpeg4VideoDescriptor]
}

case class Mpeg4AudioDescriptor(mpeg4AudioProfileAndLevel: Byte) extends TransportStreamDescriptor with ProgramStreamDescriptor
object Mpeg4AudioDescriptor {
  val codec: Codec[Mpeg4AudioDescriptor] = {
    ("MPEG-4_audio_profile_and_level" | byte)
  }.as[Mpeg4AudioDescriptor]
}

case class IodDescriptor(scopeOfIodLabel: Byte, iodLabel: Byte, initialObjectDescriptor: Byte) extends TransportStreamDescriptor with ProgramStreamDescriptor
object IodDescriptor {
  val codec: Codec[IodDescriptor] = {
    ("Scope_of_IOD_label" | byte) ::
    ("IOD_label" | byte) ::
    ("initialObjectDescriptor" | byte)
  }.as[IodDescriptor]
}

case class SlDescriptor(esId: Int) extends TransportStreamDescriptor
object SlDescriptor {
  val codec: Codec[SlDescriptor] = {
    ("ES_ID" | uint16)
  }.as[SlDescriptor]
}

case class EsIdAndChannel(esId: Int, flexMuxChannel: Int)
object EsIdAndChannel {
  implicit val codec: Codec[EsIdAndChannel] = {
    ("ES_ID" | uint16) ::
    ("FlexMuxChannel" | uint8)
  }.as[EsIdAndChannel]
}
case class FmcDescriptor(channels: Vector[EsIdAndChannel]) extends TransportStreamDescriptor with ProgramStreamDescriptor
object FmcDescriptor {
  val codec: Codec[FmcDescriptor] = {
    vector(Codec[EsIdAndChannel])
  }.as[FmcDescriptor]
}

case class ExternalEsIdDescriptor(esternalEsId: Int) extends TransportStreamDescriptor with ProgramStreamDescriptor
object ExternalEsIdDescriptor {
  val codec: Codec[ExternalEsIdDescriptor] = {
    ("External_ES_ID" | uint16)
  }.as[ExternalEsIdDescriptor]
}

case class MuxCodeDescriptor(muxCodeTableEntry: ByteVector) extends TransportStreamDescriptor with ProgramStreamDescriptor
object MuxCodeDescriptor {
  val codec: Codec[MuxCodeDescriptor] = {
     bytes
  }.as[MuxCodeDescriptor]
}

case class FmxBufferSizeDescriptor(flexMuxBufferDescriptor: ByteVector) extends TransportStreamDescriptor with ProgramStreamDescriptor
object FmxBufferSizeDescriptor {
  val codec: Codec[FmxBufferSizeDescriptor] = {
    bytes
  }.as[FmxBufferSizeDescriptor]
}

case class MultiplexBufferDescriptor(mbBufferSize: Int, tbLeakRate: Int) extends TransportStreamDescriptor with ProgramStreamDescriptor
object MultiplexBufferDescriptor {
  val codec: Codec[MultiplexBufferDescriptor] = {
    ("MB_buffer_size" | uint24) ::
    ("TB_leak_rate" | uint24)
  }.as[MultiplexBufferDescriptor]
}

case class UnknownDescriptor(tag: Int, length: Int, data: ByteVector)
object UnknownDescriptor {
  val codec: Codec[UnknownDescriptor] = {
    ("descriptor_tag" | uint8) ::
    (("descriptor_length" | uint8).flatPrepend { length =>
      ("descriptor_data" | bytes(length)).tuple
    })
  }.as[UnknownDescriptor]
}

object Descriptor {
  type Descriptor = Either[UnknownDescriptor, KnownDescriptor]

  val knownCodec: Codec[KnownDescriptor] = {
    def sized[A](c: Codec[A]) = variableSizeBytes(uint8, c)
    discriminated[KnownDescriptor].by(uint8)
      .typecase(2, sized(VideoStreamDescriptor.codec))
      .typecase(3, sized(AudioStreamDescriptor.codec))
      .typecase(4, sized(HierarchyDescriptor.codec))
      .typecase(5, sized(RegistrationDescriptor.codec))
      .typecase(6, sized(DataStreamAlignmentDescriptor.codec))
      .typecase(7, sized(TargetBackgroundGridDescriptor.codec))
      .typecase(8, sized(VideoWindowDescriptor.codec))
      .typecase(9, sized(CADescriptor.codec))
      .typecase(10, sized(Iso639LanguageDescriptor.codec))
      .typecase(11, sized(SystemClockDescriptor.codec))
      .typecase(12, sized(MultiplexBufferUtilizationDescriptor.codec))
      .typecase(13, sized(CopyrightDescriptor.codec))
      .typecase(14, sized(MaximumBitrateDescriptor.codec))
      .typecase(15, sized(PrivateDataIndicatorDescriptor.codec))
      .typecase(16, sized(SmoothingBufferDescriptor.codec))
      .typecase(17, sized(StdDescriptor.codec))
      .typecase(18, sized(IbpDescriptor.codec))
      .typecase(27, sized(Mpeg4VideoDescriptor.codec))
      .typecase(28, sized(Mpeg4AudioDescriptor.codec))
      .typecase(29, sized(IodDescriptor.codec))
      .typecase(30, sized(SlDescriptor.codec))
      .typecase(31, sized(FmcDescriptor.codec))
      .typecase(32, sized(ExternalEsIdDescriptor.codec))
      .typecase(33, sized(MuxCodeDescriptor.codec))
      .typecase(34, sized(FmxBufferSizeDescriptor.codec))
      .typecase(35, sized(MultiplexBufferDescriptor.codec))
  }

  val codec: Codec[Descriptor] = discriminatorFallback(UnknownDescriptor.codec, knownCodec)

  def lengthCodec: Codec[Int] = ("descriptor_length" | uint8)
}
