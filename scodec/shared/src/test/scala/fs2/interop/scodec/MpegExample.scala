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

package fs2.interop.scodec.examples

import scala.concurrent.duration._

import cats.effect.{Clock, IO, IOApp}

import scodec.bits.{BitVector, ByteOrdering, ByteVector}
import scodec.{Attempt, Codec, DecodeResult, Err, SizeBound, codecs}

import fs2.io.file.{Files, Path}
import fs2.interop.scodec.{CodecError, StreamDecoder}

object Mpeg extends IOApp.Simple {
  import PcapCodec._
  import MpegCodecs._

  def run: IO[Unit] = {
    val streamThroughRecordsOnly: StreamDecoder[MpegPacket] = {
      val pcapRecordStreamDecoder: StreamDecoder[PcapRecord] =
        StreamDecoder.once(pcapHeader).flatMap { header =>
          StreamDecoder.many(pcapRecord(header.ordering))
        }

      val mpegPcapDecoder: StreamDecoder[MpegPacket] = pcapRecordStreamDecoder.flatMap { record =>
        // Drop 22 byte ethernet frame header and 20 byte IPv4/udp header
        val datagramPayloadBits = record.data.drop(22 * 8).drop(20 * 8)
        val packets = codecs.vector(Codec[MpegPacket]).decode(datagramPayloadBits).map(_.value)
        packets.fold(e => StreamDecoder.raiseError(CodecError(e)), StreamDecoder.emits(_))
      }

      mpegPcapDecoder
    }

    val streamier: StreamDecoder[MpegPacket] = for {
      header <- StreamDecoder.once(pcapHeader)
      packet <- StreamDecoder.many(pcapRecordHeader(header.ordering)).flatMap { recordHeader =>
        StreamDecoder.isolate(recordHeader.includedLength * 8) {
          // Drop 22 byte ethernet frame header and 20 byte IPv4/udp header
          StreamDecoder.ignore((22 + 20) * 8) ++
            // bail on this record if we fail to parse an `MpegPacket` from it
            StreamDecoder.tryMany(mpegPacket)
        }
      }
    } yield packet

    def time[A](a: IO[A]): IO[(FiniteDuration, A)] =
      for {
        start <- Clock[IO].realTime
        result <- a
        stop <- Clock[IO].realTime
      } yield (stop - start, result)

    val filePath = Path("path/to/file")

    def countElements(decoder: StreamDecoder[_]): IO[Long] =
      Files[IO]
        .readAll(filePath)
        .through(decoder.toPipeByte)
        .compile
        .count

    def run(label: String, decoder: StreamDecoder[_]): IO[Unit] =
      time(countElements(decoder)).flatMap { case (elapsed, cnt) =>
        IO.println(s"$label stream packet count: $cnt (took $elapsed)")
      }

    run("coarse-grained", streamThroughRecordsOnly) >> run("fine-grained", streamier)
  }
}

/** Processes libpcap files.
  *
  * @see http://wiki.wireshark.org/Development/LibpcapFileFormat
  */
object PcapCodec {
  import scodec.codecs._

  private val MagicNumber = 0xa1b2c3d4L
  private val MagicNumberRev = 0xd4c3b2a1L

  private val byteOrdering: Codec[ByteOrdering] = new Codec[ByteOrdering] {
    def sizeBound = SizeBound.exact(32)

    def encode(bo: ByteOrdering) = {
      implicit val implicitByteOrdering: ByteOrdering = bo
      endiannessDependent(uint32, uint32L).encode(MagicNumber)
    }

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

  def gint16(implicit ordering: ByteOrdering): Codec[Int] =
    if (ordering == ByteOrdering.BigEndian) int16 else int16L
  def guint16(implicit ordering: ByteOrdering): Codec[Int] =
    if (ordering == ByteOrdering.BigEndian) uint16 else uint16L
  def gint32(implicit ordering: ByteOrdering): Codec[Int] =
    if (ordering == ByteOrdering.BigEndian) int32 else int32L
  def guint32(implicit ordering: ByteOrdering): Codec[Long] =
    if (ordering == ByteOrdering.BigEndian) uint32 else uint32L

  case class PcapHeader(
      ordering: ByteOrdering,
      versionMajor: Int,
      versionMinor: Int,
      thiszone: Int,
      sigfigs: Long,
      snaplen: Long,
      network: Long
  )

  val pcapHeader: Codec[PcapHeader] = "global-header" |
    ("magic_number" | byteOrdering)
      .flatPrepend { implicit ordering =>
        ("version_major" | guint16) ::
          ("version_minor" | guint16) ::
          ("thiszone" | gint32) ::
          ("sigfigs" | guint32) ::
          ("snaplen" | guint32) ::
          ("network" | guint32)
      }
      .as[PcapHeader]

  case class PcapRecordHeader(
      timestampSeconds: Long,
      timestampMicros: Long,
      includedLength: Long,
      originalLength: Long
  ) {
    def timestamp: Double = timestampSeconds + (timestampMicros / (1.second.toMicros.toDouble))
  }

  implicit def pcapRecordHeader(implicit ordering: ByteOrdering): Codec[PcapRecordHeader] = {
    ("ts_sec" | guint32) ::
      ("ts_usec" | guint32) ::
      ("incl_len" | guint32) ::
      ("orig_len" | guint32)
  }.as[PcapRecordHeader]

  case class PcapRecord(header: PcapRecordHeader, data: BitVector)

  implicit def pcapRecord(implicit ordering: ByteOrdering): Codec[PcapRecord] =
    ("record_header" | pcapRecordHeader)
      .flatPrepend { hdr =>
        ("record_data" | bits(hdr.includedLength * 8)).tuple
      }
      .as[PcapRecord]

  case class PcapFile(header: PcapHeader, records: Vector[PcapRecord])

  implicit val pcapFile: Codec[PcapFile] =
    pcapHeader
      .flatPrepend { hdr =>
        vector(pcapRecord(hdr.ordering)).tuple
      }
      .as[PcapFile]
}

object MpegCodecs {
  import scodec.codecs._

  // Define case classes that describe MPEG packets and define an HList iso for each

  case class TransportStreamHeader(
      transportStringIndicator: Boolean,
      payloadUnitStartIndicator: Boolean,
      transportPriority: Boolean,
      pid: Int,
      scramblingControl: Int,
      adaptationFieldControl: Int,
      continuityCounter: Int
  ) {
    def adaptationFieldIncluded: Boolean = adaptationFieldControl >= 2
    def payloadIncluded: Boolean = adaptationFieldControl == 1 || adaptationFieldControl == 3
  }

  case class AdaptationFieldFlags(
      discontinuity: Boolean,
      randomAccess: Boolean,
      priority: Boolean,
      pcrFlag: Boolean,
      opcrFlag: Boolean,
      splicingPointFlag: Boolean,
      transportPrivateDataFlag: Boolean,
      adaptationFieldExtension: Boolean
  )

  case class AdaptationField(
      flags: AdaptationFieldFlags,
      pcr: Option[BitVector],
      opcr: Option[BitVector],
      spliceCountdown: Option[Int]
  )

  case class MpegPacket(
      header: TransportStreamHeader,
      adaptationField: Option[AdaptationField],
      payload: Option[ByteVector]
  )

  implicit val transportStreamHeader: Codec[TransportStreamHeader] = {
    ("syncByte" | constant(0x47)) ~>
      (("transportStringIndicator" | bool) ::
        ("payloadUnitStartIndicator" | bool) ::
        ("transportPriority" | bool) ::
        ("pid" | uint(13)) ::
        ("scramblingControl" | uint2) ::
        ("adaptationFieldControl" | uint2) ::
        ("continuityCounter" | uint4))
  }.as[TransportStreamHeader]

  implicit val adaptationFieldFlags: Codec[AdaptationFieldFlags] = {
    ("discontinuity" | bool) ::
      ("randomAccess" | bool) ::
      ("priority" | bool) ::
      ("pcrFlag" | bool) ::
      ("opcrFlag" | bool) ::
      ("splicingPointFlag" | bool) ::
      ("transportPrivateDataFlag" | bool) ::
      ("adaptationFieldExtension" | bool)
  }.as[AdaptationFieldFlags]

  implicit val adaptationField: Codec[AdaptationField] =
    ("adaptation_flags" | adaptationFieldFlags)
      .flatPrepend { flags =>
        ("pcr" | conditional(flags.pcrFlag, bits(48))) ::
          ("opcr" | conditional(flags.opcrFlag, bits(48))) ::
          ("spliceCountdown" | conditional(flags.splicingPointFlag, int8))
      }
      .as[AdaptationField]

  implicit val mpegPacket: Codec[MpegPacket] =
    ("header" | transportStreamHeader)
      .flatPrepend { hdr =>
        ("adaptation_field" | conditional(hdr.adaptationFieldIncluded, adaptationField)) ::
          ("payload" | conditional(hdr.payloadIncluded, bytes(184)))
      }
      .as[MpegPacket]
}
