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

package fs2
package protocols
package mpeg
package transport
package psi

import Descriptor._

import scodec._
import scodec.bits._

import org.scalacheck.{Arbitrary, Gen, Prop}

class DescriptorTest extends Fs2Suite {
  import DescriptorTestData._

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters
      .withMinSuccessfulTests(1000)

  test("support relevant descriptors which handles decoding and encoding for valid values") {
    Prop.forAll((d: Descriptor) => roundtrip(Descriptor.codec, d))
  }

  private def roundtrip[A](codec: Codec[A], value: A) = {
    val encoded = codec.encode(value)
    val Attempt.Successful(DecodeResult(decoded, remainder)) = codec.decode(encoded.require)
    assertEquals(remainder, BitVector.empty)
    assertEquals(decoded, value)
  }
}

object DescriptorTestData {

  def genMpeg1Only(flag: Boolean): Gen[Option[Mpeg1Only]] =
    if (!flag) None
    else
      for {
        profileAndLevelIndication <- Gen.chooseNum(0, 255)
        chromaFormat <- Gen.chooseNum(0, 3)
        frameRateExtensionFlag <- Gen.oneOf(true, false)
      } yield Some(Mpeg1Only(profileAndLevelIndication, chromaFormat, frameRateExtensionFlag))

  lazy val genVideoStreamDescriptor: Gen[VideoStreamDescriptor] = for {
    multipleFrameRateFlag <- Gen.oneOf(true, false)
    frameRateCode <- Gen.chooseNum(0, 15)
    mpeg1OnlyFlag <- Gen.oneOf(true, false)
    constrainedParameter <- Gen.oneOf(true, false)
    stillPictureFlag <- Gen.oneOf(true, false)
    mpeg1Only <- genMpeg1Only(mpeg1OnlyFlag)
  } yield VideoStreamDescriptor(
    multipleFrameRateFlag,
    frameRateCode,
    mpeg1OnlyFlag,
    constrainedParameter,
    stillPictureFlag,
    mpeg1Only
  )

  lazy val genAudioStreamDescriptor: Gen[AudioStreamDescriptor] = for {
    freeFormatFlag <- Gen.oneOf(true, false)
    id <- Gen.oneOf(true, false)
    layer <- Gen.chooseNum(0, 3)
    variableRateAudioIndicator <- Gen.oneOf(true, false)
  } yield AudioStreamDescriptor(freeFormatFlag, id, layer, variableRateAudioIndicator)

  lazy val genHierarchyType: Gen[HierarchyType] = Gen.oneOf(
    HierarchyType.SpatialScalability,
    HierarchyType.SnrScalability,
    HierarchyType.TemporalScalability,
    HierarchyType.DataPartitioning,
    HierarchyType.ExtensionBitstream,
    HierarchyType.PrivateStream,
    HierarchyType.MultiViewProfile,
    HierarchyType.Reserved(0),
    HierarchyType.BaseLayer
  )

  lazy val genHierarchyDescriptor: Gen[HierarchyDescriptor] = for {
    hierarchyType <- genHierarchyType
    hierarchyLayerIndex <- Gen.chooseNum(0, 63)
    hierarchyEmbeddedLayerIndex <- Gen.chooseNum(0, 63)
    hierarchyChannel <- Gen.chooseNum(0, 63)
  } yield HierarchyDescriptor(
    hierarchyType,
    hierarchyLayerIndex,
    hierarchyEmbeddedLayerIndex,
    hierarchyChannel
  )

  lazy val genRegistrationDescriptor: Gen[RegistrationDescriptor] = for {
    length <- Gen.chooseNum(4, 255)
    formatIdentifier <- Gen.listOfN(4, Gen.chooseNum(0, 255))
    additionalIdentificationInfo <- Gen.listOfN(length - 4, Gen.chooseNum(0, 255))
  } yield RegistrationDescriptor(
    ByteVector(formatIdentifier: _*),
    ByteVector(additionalIdentificationInfo: _*)
  )

  lazy val genDataStreamAlignmentDescriptor: Gen[DataStreamAlignmentDescriptor] = for {
    alignmentType <- Gen.oneOf(
      AlignmentType.Reserved(0),
      AlignmentType.SliceOrVideoAccessUnit,
      AlignmentType.VideoAccessUnit,
      AlignmentType.GopOrSeq,
      AlignmentType.Seq
    )
  } yield DataStreamAlignmentDescriptor(alignmentType)

  lazy val genTargetBackgroundGridDescriptor: Gen[TargetBackgroundGridDescriptor] = for {
    horizontalSize <- Gen.chooseNum(0, 16383)
    verticalSize <- Gen.chooseNum(0, 16383)
    aspectRatioInformation <- Gen.choose(0, 15)
  } yield TargetBackgroundGridDescriptor(horizontalSize, verticalSize, aspectRatioInformation)

  lazy val genVideoWindowDescriptor: Gen[VideoWindowDescriptor] = for {
    horizontalOffset <- Gen.chooseNum(0, 16383)
    verticalOffset <- Gen.chooseNum(0, 16383)
    windowPriority <- Gen.choose(0, 15)
  } yield VideoWindowDescriptor(horizontalOffset, verticalOffset, windowPriority)

  lazy val genCADescriptor: Gen[CADescriptor] = for {
    length <- Gen.chooseNum(4, 255)
    caSystemId <- Gen.chooseNum(0, 65535)
    caPid <- Gen.choose(0, 8191)
    privateData <- Gen.listOfN(length - 4, Gen.chooseNum(0, 255))
  } yield CADescriptor(caSystemId, Pid(caPid), ByteVector(privateData: _*))

  lazy val genLanguageField: Gen[LanguageField] = for {
    iso639LanguageCode <- Gen.listOfN(3, Gen.alphaChar)
    audioType <- Gen.oneOf(
      AudioType.Undefined,
      AudioType.CleanEffects,
      AudioType.HearingImpaired,
      AudioType.VisualImpairedCommentary,
      AudioType.Reserved(4)
    )
  } yield LanguageField(iso639LanguageCode.mkString, audioType)

  lazy val genIso639LanguageDescriptor: Gen[Iso639LanguageDescriptor] = for {
    numberOfLanguagueField <- Gen.chooseNum(0, 63)
    languageFields <- Gen.listOfN(numberOfLanguagueField, genLanguageField)
  } yield Iso639LanguageDescriptor(languageFields.toVector)

  lazy val genSystemClockDescriptor: Gen[SystemClockDescriptor] = for {
    externalClockReferenceIndicator <- Gen.oneOf(true, false)
    clockAccuracyInteger <- Gen.oneOf(0, 63)
    clockAccuracyExponent <- Gen.oneOf(0, 7)
  } yield SystemClockDescriptor(
    externalClockReferenceIndicator,
    clockAccuracyInteger,
    clockAccuracyExponent
  )

  lazy val genMultiplexBufferUtilizationDescriptor: Gen[MultiplexBufferUtilizationDescriptor] =
    for {
      boundValidFlag <- Gen.oneOf(true, false)
      ltwOffsetLowerBound <- Gen.oneOf(0, 32767)
      ltwOffsetUpperBound <- Gen.oneOf(0, 16383)
    } yield MultiplexBufferUtilizationDescriptor(
      boundValidFlag,
      ltwOffsetLowerBound,
      ltwOffsetUpperBound
    )

  lazy val genCopyrightDescriptor: Gen[CopyrightDescriptor] = for {
    length <- Gen.chooseNum(4, 255)
    copyrightIdentifier <- Gen.listOfN(4, Gen.chooseNum(0, 255))
    additionalCopyrightInfo <- Gen.listOfN(length - 4, Gen.chooseNum(0, 255))
  } yield CopyrightDescriptor(
    ByteVector(copyrightIdentifier: _*),
    ByteVector(additionalCopyrightInfo: _*)
  )

  lazy val genMaximumBitrateDescriptor: Gen[MaximumBitrateDescriptor] = for {
    maximumBitrate <- Gen.chooseNum(0, 4194303)
  } yield MaximumBitrateDescriptor(maximumBitrate)

  lazy val genPrivateDataIndicatorDescriptor: Gen[PrivateDataIndicatorDescriptor] = for {
    privateDataIndicator <- Gen.listOfN(4, Gen.chooseNum(0, 255))
  } yield PrivateDataIndicatorDescriptor(ByteVector(privateDataIndicator: _*))

  lazy val genSmoothingBufferDescriptor: Gen[SmoothingBufferDescriptor] = for {
    sbLeakRate <- Gen.chooseNum(0, 4194303)
    sbSize <- Gen.chooseNum(0, 4194303)
  } yield SmoothingBufferDescriptor(sbLeakRate, sbSize)

  lazy val genStdDescriptor: Gen[StdDescriptor] =
    for { leakValidFlag <- Gen.oneOf(true, false) } yield StdDescriptor(leakValidFlag)

  lazy val genIbpDescriptor: Gen[IbpDescriptor] = for {
    closedGopFlag <- Gen.oneOf(true, false)
    identicalGopFlag <- Gen.oneOf(true, false)
    maxGopLength <- Gen.chooseNum(0, 16383)
  } yield IbpDescriptor(closedGopFlag, identicalGopFlag, maxGopLength)

  lazy val genMpeg4VideoDescriptor: Gen[Mpeg4VideoDescriptor] =
    for { mpeg4VisualProfileAndLevel <- Gen.chooseNum(0, 255) } yield Mpeg4VideoDescriptor(
      mpeg4VisualProfileAndLevel.toByte
    )

  lazy val genMpeg4AudioDescriptor: Gen[Mpeg4AudioDescriptor] =
    for { mpeg4AudioProfileAndLevel <- Gen.chooseNum(0, 255) } yield Mpeg4AudioDescriptor(
      mpeg4AudioProfileAndLevel.toByte
    )

  lazy val genIodDescriptor: Gen[IodDescriptor] = for {
    scopeOfIodLabel <- Gen.chooseNum(0, 255)
    iodLabel <- Gen.chooseNum(0, 255)
    initialObjectDescriptor <- Gen.chooseNum(0, 255)
  } yield IodDescriptor(scopeOfIodLabel.toByte, iodLabel.toByte, initialObjectDescriptor.toByte)

  lazy val genSlDescriptor: Gen[SlDescriptor] =
    for { esId <- Gen.chooseNum(0, 65535) } yield SlDescriptor(esId: Int)

  lazy val genEsIdAndChannel: Gen[EsIdAndChannel] = for {
    esId <- Gen.chooseNum(0, 65535)
    flexMuxChannel <- Gen.chooseNum(0, 255)
  } yield EsIdAndChannel(esId, flexMuxChannel)

  lazy val genFmcDescriptor: Gen[FmcDescriptor] = for {
    numberOf <- Gen.chooseNum(0, 85)
    channels <- Gen.listOfN(numberOf, genEsIdAndChannel)
  } yield FmcDescriptor(channels.toVector)

  lazy val genExternalEsIdDescriptor: Gen[ExternalEsIdDescriptor] =
    for { externalEsId <- Gen.chooseNum(0, 65535) } yield ExternalEsIdDescriptor(externalEsId)

  lazy val genMuxCodeDescriptor: Gen[MuxCodeDescriptor] = for {
    length <- Gen.chooseNum(0, 255)
    muxCodeTableEntry <- Gen.listOfN(length, Gen.chooseNum(0, 255))
  } yield MuxCodeDescriptor(ByteVector(muxCodeTableEntry: _*))

  lazy val genFmxBufferSizeDescriptor: Gen[FmxBufferSizeDescriptor] = for {
    length <- Gen.chooseNum(0, 255)
    flexMuxBufferDescriptor <- Gen.listOfN(length, Gen.chooseNum(0, 255))
  } yield FmxBufferSizeDescriptor(ByteVector(flexMuxBufferDescriptor: _*))

  lazy val genMultiplexBufferDescriptor: Gen[MultiplexBufferDescriptor] = for {
    mbBufferSize <- Gen.chooseNum(0, 16777215)
    tbLeakRate <- Gen.chooseNum(0, 16777215)
  } yield MultiplexBufferDescriptor(mbBufferSize, tbLeakRate)

  lazy val genKnownDescriptor: Gen[KnownDescriptor] = Gen.oneOf(
    genVideoStreamDescriptor,
    genAudioStreamDescriptor,
    genHierarchyDescriptor,
    genRegistrationDescriptor,
    genDataStreamAlignmentDescriptor,
    genTargetBackgroundGridDescriptor,
    genVideoWindowDescriptor,
    genCADescriptor,
    genIso639LanguageDescriptor,
    genSystemClockDescriptor,
    genMultiplexBufferUtilizationDescriptor,
    genCopyrightDescriptor,
    genMaximumBitrateDescriptor,
    genPrivateDataIndicatorDescriptor,
    genSmoothingBufferDescriptor,
    genStdDescriptor,
    genIbpDescriptor,
    genMpeg4VideoDescriptor,
    genMpeg4AudioDescriptor,
    genIodDescriptor,
    genSlDescriptor,
    genFmcDescriptor,
    genExternalEsIdDescriptor,
    genMuxCodeDescriptor,
    genFmxBufferSizeDescriptor,
    genMultiplexBufferDescriptor
  )

  lazy val genUnknownDescriptor: Gen[UnknownDescriptor] = for {
    tag <- Gen.chooseNum(36, 255)
    length <- Gen.chooseNum(0, 255)
    data <- Gen.listOfN(length, Gen.chooseNum(0, 255))
  } yield UnknownDescriptor(tag, length, ByteVector(data: _*))

  lazy val genDescriptor: Gen[Descriptor] =
    Gen.oneOf(genKnownDescriptor, genUnknownDescriptor).map {
      case known: KnownDescriptor     => Right(known)
      case unknown: UnknownDescriptor => Left(unknown)
    }

  implicit lazy val arbitraryDescriptor: Arbitrary[Descriptor] = Arbitrary(genDescriptor)
}
