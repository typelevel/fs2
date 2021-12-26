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

package fs2
package compression

import cats.effect.{Async, Deferred, Sync}
import cats.syntax.all._

import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

object CrcPipe {

  def apply[F[_]](deferredCrc: Deferred[F, Long]): Pipe[F, Byte, Byte] = {
    def pull(crcBuilder: CRC32): Stream[F, Byte] => Pull[F, Byte, Long] =
      _.pull.uncons.flatMap {
        case None => Pull.pure(crcBuilder.getValue())
        case Some((c: Chunk[Byte], rest: Stream[F, Byte])) =>
          val slice = c.toArraySlice
          crcBuilder.update(slice.values, slice.offset, slice.length)
          for {
            _ <- Pull.output(c)
            hexString <- pull(crcBuilder)(rest)
          } yield hexString
      }

    def calculateCrcOf(input: Stream[F, Byte]): Pull[F, Byte, Unit] =
      for {
        crcBuilder <- Pull.pure(new CRC32)
        crc <- pull(crcBuilder)(input)
        _ <- Pull.eval(deferredCrc.complete(crc))
      } yield ()

    calculateCrcOf(_).stream
  }

}

object CountPipe {

  def apply[F[_]](deferredCount: Deferred[F, Long])(implicit F: Sync[F]): Pipe[F, Byte, Byte] = {
    def pull(count: Long): Stream[F, Byte] => Pull[F, Byte, Long] =
      _.pull.uncons.flatMap {
        case None => Pull.eval(F.delay(count))
        case Some((c: Chunk[Byte], rest: Stream[F, Byte])) =>
          for {
            _ <- Pull.output(c)
            hexString <- pull(count + c.size)(rest)
          } yield hexString
      }

    def calculateSizeOf(input: Stream[F, Byte]): Pull[F, Byte, Unit] =
      for {
        count <- pull(0)(input)
        _ <- Pull.eval(deferredCount.complete(count))
      } yield ()

    calculateSizeOf(_).stream
  }

}

class Gzip[F[_]](implicit F: Async[F]) {

  def gzip(
      fileName: Option[String],
      modificationTime: Option[FiniteDuration],
      comment: Option[String],
      deflate: Pipe[F, Byte, Byte],
      deflateParams: DeflateParams
  ): Pipe[F, Byte, Byte] =
    stream =>
      deflateParams match {
        case params: DeflateParams if params.header == ZLibParams.Header.GZIP =>
          for {
            crc <- Stream.eval(F.deferred[Long])
            count <- Stream.eval(F.deferred[Long])
            result <- _gzip_header(
              fileName,
              modificationTime,
              comment,
              params.level.juzDeflaterLevel,
              params.fhCrcEnabled
            ) ++
              stream.through(CrcPipe(crc)).through(CountPipe(count)).through(deflate) ++
              Stream.eval(crc.get).flatMap { crc =>
                Stream.eval(count.get).flatMap { count =>
                  _gzip_trailer(count, crc)
                }
              }

          } yield result

        case params: DeflateParams =>
          Stream.raiseError[F](
            new ZipException(
              s"${ZLibParams.Header.GZIP} header type required, not ${params.header}."
            )
          )
      }

  private def _gzip_header(
      fileName: Option[String],
      modificationTime: Option[FiniteDuration],
      comment: Option[String],
      deflateLevel: Int,
      fhCrcEnabled: Boolean
  ): Stream[F, Byte] = {
    // See RFC 1952: https://www.ietf.org/rfc/rfc1952.txt
    val secondsSince197001010000: Long =
      modificationTime.map(_.toSeconds).getOrElse(0)
    val header = Array[Byte](
      gzipMagicFirstByte, // ID1: Identification 1
      gzipMagicSecondByte, // ID2: Identification 2
      gzipCompressionMethod.DEFLATE, // CM: Compression Method
      ((if (fhCrcEnabled) gzipFlag.FHCRC else zeroByte) + // FLG: Header CRC
        fileName.map(_ => gzipFlag.FNAME).getOrElse(zeroByte) + // FLG: File name
        comment.map(_ => gzipFlag.FCOMMENT).getOrElse(zeroByte)).toByte, // FLG: Comment
      (secondsSince197001010000 & 0xff).toByte, // MTIME: Modification Time
      ((secondsSince197001010000 >> 8) & 0xff).toByte,
      ((secondsSince197001010000 >> 16) & 0xff).toByte,
      ((secondsSince197001010000 >> 24) & 0xff).toByte,
      deflateLevel match { // XFL: Extra flags
        case 9 => gzipExtraFlag.DEFLATE_MAX_COMPRESSION_SLOWEST_ALGO
        case 1 => gzipExtraFlag.DEFLATE_FASTEST_ALGO
        case _ => zeroByte
      },
      gzipOperatingSystem.THIS
    ) // OS: Operating System
    val crc32 = new CRC32
    crc32.update(header)
    val fileNameEncoded = fileName.map { string =>
      val bytes = string.replaceAll("\u0000", "_").getBytes(StandardCharsets.ISO_8859_1)
      crc32.update(bytes)
      crc32.update(zeroByte.toInt)
      bytes
    }
    val commentEncoded = comment.map { string =>
      val bytes = string.replaceAll("\u0000", " ").getBytes(StandardCharsets.ISO_8859_1)
      crc32.update(bytes)
      crc32.update(zeroByte.toInt)
      bytes
    }
    val crc32Value = crc32.getValue()

    val crc16 =
      if (fhCrcEnabled)
        Array[Byte](
          (crc32Value & 0xff).toByte,
          ((crc32Value >> 8) & 0xff).toByte
        )
      else
        Array.emptyByteArray

    Stream.chunk(moveAsChunkBytes(header)) ++
      fileNameEncoded
        .map(bytes => Stream.chunk(moveAsChunkBytes(bytes)) ++ Stream.emit(zeroByte))
        .getOrElse(Stream.empty) ++
      commentEncoded
        .map(bytes => Stream.chunk(moveAsChunkBytes(bytes)) ++ Stream.emit(zeroByte))
        .getOrElse(Stream.empty) ++
      Stream.chunk(moveAsChunkBytes(crc16))
  }

  private def _gzip_trailer(bytesIn: Long, crc: Long): Stream[F, Byte] = {
    // See RFC 1952: https://www.ietf.org/rfc/rfc1952.txt
    val crc32Value = crc
    val trailer = Array[Byte](
      (crc32Value & 0xff).toByte, // CRC-32: Cyclic Redundancy Check
      ((crc32Value >> 8) & 0xff).toByte,
      ((crc32Value >> 16) & 0xff).toByte,
      ((crc32Value >> 24) & 0xff).toByte,
      (bytesIn & 0xff).toByte, // ISIZE: Input size
      ((bytesIn >> 8) & 0xff).toByte,
      ((bytesIn >> 16) & 0xff).toByte,
      ((bytesIn >> 24) & 0xff).toByte
    )
    Stream.chunk(moveAsChunkBytes(trailer))
  }

  def gunzip(
      inflate: Stream[F, Byte] => Stream[F, (Stream[F, Byte], Deferred[F, Chunk[Byte]])],
      inflateParams: InflateParams
  ): Stream[F, Byte] => Stream[F, GunzipResult[F]] =
    stream =>
      inflateParams match {
        case params: InflateParams if params.header == ZLibParams.Header.GZIP =>
          stream.pull
            .unconsN(gzipHeaderBytes)
            .flatMap {
              case Some((mandatoryHeaderChunk, streamAfterMandatoryHeader)) =>
                _gunzip_matchMandatoryHeader(
                  mandatoryHeaderChunk,
                  streamAfterMandatoryHeader,
                  new CRC32,
                  inflate
                )
              case None =>
                Pull.output1(GunzipResult.create(Stream.raiseError(new EOFException())))
            }
            .stream

        case params: InflateParams =>
          Stream.raiseError(
            new ZipException(
              s"${ZLibParams.Header.GZIP} header type required, not ${params.header}."
            )
          )
      }

  private def _gunzip_matchMandatoryHeader(
      mandatoryHeaderChunk: Chunk[Byte],
      streamAfterMandatoryHeader: Stream[F, Byte],
      headerCrc32: CRC32,
      inflate: Stream[F, Byte] => Stream[F, (Stream[F, Byte], Deferred[F, Chunk[Byte]])]
  ) =
    (mandatoryHeaderChunk.size, mandatoryHeaderChunk.toArraySlice.values) match {
      case (
            `gzipHeaderBytes`,
            Array(
              `gzipMagicFirstByte`,
              `gzipMagicSecondByte`,
              gzipCompressionMethod.DEFLATE,
              flags,
              _,
              _,
              _,
              _,
              _
            )
          ) if gzipFlag.reserved5(flags) =>
        Pull.output1(
          GunzipResult.create(
            Stream.raiseError(
              new ZipException("Unsupported gzip flag reserved bit 5 is non-zero")
            )
          )
        )
      case (
            `gzipHeaderBytes`,
            Array(
              `gzipMagicFirstByte`,
              `gzipMagicSecondByte`,
              gzipCompressionMethod.DEFLATE,
              flags,
              _,
              _,
              _,
              _,
              _
            )
          ) if gzipFlag.reserved6(flags) =>
        Pull.output1(
          GunzipResult.create(
            Stream.raiseError(
              new ZipException("Unsupported gzip flag reserved bit 6 is non-zero")
            )
          )
        )
      case (
            `gzipHeaderBytes`,
            Array(
              `gzipMagicFirstByte`,
              `gzipMagicSecondByte`,
              gzipCompressionMethod.DEFLATE,
              flags,
              _,
              _,
              _,
              _,
              _
            )
          ) if gzipFlag.reserved7(flags) =>
        Pull.output1(
          GunzipResult.create(
            Stream.raiseError(
              new ZipException("Unsupported gzip flag reserved bit 7 is non-zero")
            )
          )
        )
      case (
            `gzipHeaderBytes`,
            header @ Array(
              `gzipMagicFirstByte`,
              `gzipMagicSecondByte`,
              gzipCompressionMethod.DEFLATE,
              flags,
              _,
              _,
              _,
              _,
              _,
              _
            )
          ) =>
        headerCrc32.update(header)
        val secondsSince197001010000 =
          unsignedToLong(header(4), header(5), header(6), header(7))
        _gunzip_readOptionalHeader(
          streamAfterMandatoryHeader,
          flags,
          headerCrc32,
          secondsSince197001010000,
          inflate
        ).pull.uncons1
          .flatMap {
            case Some((gunzipResult, _)) =>
              Pull.output1(gunzipResult)
            case None =>
              Pull.output1(GunzipResult.create(Stream.raiseError(new EOFException())))
          }
      case (
            `gzipHeaderBytes`,
            Array(
              `gzipMagicFirstByte`,
              `gzipMagicSecondByte`,
              compressionMethod,
              _,
              _,
              _,
              _,
              _,
              _,
              _
            )
          ) =>
        Pull.output1(
          GunzipResult.create(
            Stream.raiseError(
              new ZipException(
                s"Unsupported gzip compression method: $compressionMethod"
              )
            )
          )
        )
      case _ =>
        Pull.output1(
          GunzipResult.create(Stream.raiseError(new ZipException("Not in gzip format")))
        )
    }

  private def _gunzip_readOptionalHeader(
      streamAfterMandatoryHeader: Stream[F, Byte],
      flags: Byte,
      headerCrc32: CRC32,
      secondsSince197001010000: Long,
      inflate: Stream[F, Byte] => Stream[F, (Stream[F, Byte], Deferred[F, Chunk[Byte]])]
  ): Stream[F, GunzipResult[F]] =
    streamAfterMandatoryHeader
      .through(_gunzip_skipOptionalExtraField(gzipFlag.fextra(flags), headerCrc32))
      .through(
        _gunzip_readOptionalStringField(
          gzipFlag.fname(flags),
          headerCrc32,
          "file name",
          fileNameBytesSoftLimit
        )
      )
      .flatMap { case (fileName, streamAfterFileName) =>
        streamAfterFileName
          .through(
            _gunzip_readOptionalStringField(
              gzipFlag.fcomment(flags),
              headerCrc32,
              "file comment",
              fileCommentBytesSoftLimit
            )
          )
          .flatMap { case (comment, streamAfterComment) =>
            Stream.emit(
              GunzipResult.create(
                modificationTimeEpoch =
                  if (secondsSince197001010000 != 0)
                    Some(FiniteDuration(secondsSince197001010000, TimeUnit.SECONDS))
                  else None,
                fileName = fileName,
                comment = comment,
                content = Stream.eval(F.deferred[Long]).flatMap { contentCrc32 =>
                  Stream.eval(F.deferred[Long]).flatMap { count =>
                    inflate(
                      streamAfterComment
                        .through(
                          _gunzip_validateHeader(
                            (flags & gzipFlag.FHCRC) == gzipFlag.FHCRC,
                            headerCrc32
                          )
                        )
                    ).flatMap { case (inflated, after) =>
                      inflated
                        .through(CrcPipe(contentCrc32))
                        .through(CountPipe(count)) ++
                        _gunzip_validateTrailer(
                          after,
                          contentCrc32,
                          count
                        )
                    }
                  }
                }
              )
            )
          }
      }

  private def _gunzip_skipOptionalExtraField(
      isPresent: Boolean,
      crc32Builder: CRC32
  ): Pipe[F, Byte, Byte] =
    stream =>
      if (isPresent) {
        stream.pull
          .unconsN(gzipOptionalExtraFieldLengthBytes)
          .flatMap {
            case Some((optionalExtraFieldLengthChunk, streamAfterOptionalExtraFieldLength)) =>
              (
                optionalExtraFieldLengthChunk.size,
                optionalExtraFieldLengthChunk.toArraySlice.values
              ) match {
                case (
                      `gzipOptionalExtraFieldLengthBytes`,
                      lengthBytes @ Array(firstByte, secondByte)
                    ) =>
                  crc32Builder.update(lengthBytes)
                  val optionalExtraFieldLength = unsignedToInt(firstByte, secondByte)
                  streamAfterOptionalExtraFieldLength.pull
                    .unconsN(optionalExtraFieldLength)
                    .flatMap {
                      case Some((optionalExtraFieldChunk, streamAfterOptionalExtraField)) =>
                        val fieldBytes = optionalExtraFieldChunk.toArraySlice
                        crc32Builder.update(fieldBytes.values, fieldBytes.offset, fieldBytes.length)
                        Pull.output1(streamAfterOptionalExtraField)
                      case None =>
                        Pull.raiseError(
                          new ZipException("Failed to read optional extra field header")
                        )
                    }

                case _ =>
                  Pull.raiseError(
                    new ZipException("Failed to read optional extra field header length")
                  )
              }
            case None =>
              Pull.raiseError(new EOFException())
          }
          .stream
          .flatten
      } else stream

  private def _gunzip_readOptionalStringField(
      isPresent: Boolean,
      crc32: CRC32,
      fieldName: String,
      fieldBytesSoftLimit: Int
  ): Stream[F, Byte] => Stream[F, (Option[String], Stream[F, Byte])] =
    stream =>
      if (isPresent)
        unconsUntil[Byte](_ == zeroByte, fieldBytesSoftLimit)
          .apply(stream)
          .flatMap {
            case Some((chunk, rest)) =>
              Pull.output1(
                (
                  if (chunk.isEmpty)
                    Some("")
                  else {
                    val bytesChunk = chunk.toArraySlice
                    crc32.update(bytesChunk.values, bytesChunk.offset, bytesChunk.length)
                    Some(
                      new String(
                        bytesChunk.values,
                        bytesChunk.offset,
                        bytesChunk.length,
                        StandardCharsets.ISO_8859_1
                      )
                    )
                  },
                  rest
                    .dropThrough { byte =>
                      // Will also call crc32.update(byte) for the zeroByte dropped hereafter.
                      crc32.update(byte.toInt)
                      byte != zeroByte
                    }
                )
              )
            case None =>
              Pull.output1(
                (
                  Option.empty[String],
                  Stream.raiseError(new ZipException(s"Failed to read $fieldName field"))
                )
              )
          }
          .stream
      else Stream.emit((Option.empty[String], stream))

  private def _gunzip_validateHeader(
      isPresent: Boolean,
      crc32Builder: CRC32
  ): Pipe[F, Byte, Byte] =
    stream =>
      if (isPresent)
        stream.pull
          .unconsN(gzipHeaderCrcBytes)
          .flatMap {
            case Some((headerCrcChunk, streamAfterHeaderCrc)) =>
              val expectedHeaderCrc16 = unsignedToInt(headerCrcChunk(0), headerCrcChunk(1))
              val actualHeaderCrc16 = crc32Builder.getValue() & 0xffff
              if (expectedHeaderCrc16 != actualHeaderCrc16)
                Pull.raiseError(new ZipException("Header failed CRC validation"))
              else
                Pull.output1(streamAfterHeaderCrc)
            case None =>
              Pull.raiseError(new ZipException("Failed to read header CRC"))
          }
          .stream
          .flatten
      else stream

  private def _gunzip_validateTrailer(
      trailerStream: Deferred[F, Chunk[Byte]],
      crc32: Deferred[F, Long],
      actualInputSize: Deferred[F, Long]
  ): Stream[F, Byte] =
    Stream
      .eval {
        trailerStream.get.flatMap[Unit] { trailerChunk =>
          if (trailerChunk.size != gzipTrailerBytes) {
            F.raiseError(new ZipException(s"Failed to read trailer (1): ${trailerChunk}"))
          } else {
            crc32.get.flatMap { crc32 =>
              actualInputSize.get.flatMap { actualInputSize =>
                val expectedInputCrc32 =
                  unsignedToLong(
                    trailerChunk(0),
                    trailerChunk(1),
                    trailerChunk(2),
                    trailerChunk(3)
                  ) & 0xffffffff

                val actualInputCrc32 = crc32

                val expectedInputSize =
                  unsignedToLong(
                    trailerChunk(4),
                    trailerChunk(5),
                    trailerChunk(6),
                    trailerChunk(7)
                  )

                if (expectedInputCrc32 != actualInputCrc32) {
                  F.raiseError[Unit](new ZipException("Content failed CRC validation"))
                } else if (expectedInputSize != actualInputSize) {
                  F.raiseError[Unit](new ZipException("Content failed size validation"))
                } else {
                  F.unit
                }
              }
            }
          }
        }
      }
      .flatMap(_ => Stream.empty)

  /** Like Stream.unconsN, but returns a chunk of elements that do not satisfy the predicate, splitting chunk as necessary.
    * Elements will not be dropped after the soft limit is breached.
    *
    * `Pull.pure(None)` is returned if the end of the source stream is reached.
    */
  private def unconsUntil[O](
      predicate: O => Boolean,
      softLimit: Int
  ): Stream[F, O] => Pull[F, INothing, Option[(Chunk[O], Stream[F, O])]] =
    stream => {
      def go(
          acc: Chunk[O],
          rest: Stream[F, O],
          size: Int = 0
      ): Pull[F, INothing, Option[(Chunk[O], Stream[F, O])]] =
        rest.pull.uncons.flatMap {
          case None =>
            Pull.pure(None)
          case Some((hd, tl)) =>
            hd.indexWhere(predicate) match {
              case Some(i) =>
                val (pfx, sfx) = hd.splitAt(i)
                Pull.pure(Some((acc ++ pfx) -> (Stream.chunk(sfx) ++ tl)))
              case None =>
                val newSize = size + hd.size
                if (newSize < softLimit) go(acc ++ hd, tl, newSize)
                else Pull.pure(Some((acc ++ hd) -> tl))
            }
        }

      go(Chunk.empty, stream)
    }

  private val gzipHeaderBytes = 10
  private val gzipMagicFirstByte: Byte = 0x1f.toByte
  private val gzipMagicSecondByte: Byte = 0x8b.toByte
  private object gzipCompressionMethod {
    val DEFLATE: Byte = 8.toByte // Deflater.DEFLATED.toByte
  }
  private object gzipFlag {
    def apply(flags: Byte, flag: Byte): Boolean = (flags & flag) == flag
    def apply(flags: Byte, flag: Int): Boolean = (flags & flag) == flag

    def ftext(flags: Byte): Boolean = apply(flags, FTEXT)
    def fhcrc(flags: Byte): Boolean = apply(flags, FHCRC)
    def fextra(flags: Byte): Boolean = apply(flags, FEXTRA)
    def fname(flags: Byte): Boolean = apply(flags, FNAME)
    def fcomment(flags: Byte): Boolean = apply(flags, FCOMMENT)
    def reserved5(flags: Byte): Boolean = apply(flags, RESERVED_BIT_5)
    def reserved6(flags: Byte): Boolean = apply(flags, RESERVED_BIT_6)
    def reserved7(flags: Byte): Boolean = apply(flags, RESERVED_BIT_7)

    val FTEXT: Byte = 1
    val FHCRC: Byte = 2
    val FEXTRA: Byte = 4
    val FNAME: Byte = 8
    val FCOMMENT: Byte = 16
    val RESERVED_BIT_5 = 32
    val RESERVED_BIT_6 = 64
    val RESERVED_BIT_7: Int = 128
  }
  private object gzipExtraFlag {
    val DEFLATE_MAX_COMPRESSION_SLOWEST_ALGO: Byte = 2
    val DEFLATE_FASTEST_ALGO: Byte = 4
  }
  private val gzipOptionalExtraFieldLengthBytes = 2
  private val gzipHeaderCrcBytes = 2
  private object gzipOperatingSystem {
    val FAT_FILESYSTEM: Byte = 0
    val AMIGA: Byte = 1
    val VMS: Byte = 2
    val UNIX: Byte = 3
    val VM_CMS: Byte = 4
    val ATARI_TOS: Byte = 5
    val HPFS_FILESYSTEM: Byte = 6
    val MACINTOSH: Byte = 7
    val Z_SYSTEM: Byte = 8
    val CP_M: Byte = 9
    val TOPS_20: Byte = 10
    val NTFS_FILESYSTEM: Byte = 11
    val QDOS: Byte = 12
    val ACORN_RISCOS: Byte = 13
    val UNKNOWN: Byte = 255.toByte

    val THIS: Byte = System.getProperty("os.name") match {
      case null => UNKNOWN
      case s =>
        s.toLowerCase() match {
          case name if name.indexOf("nux") > 0  => UNIX
          case name if name.indexOf("nix") > 0  => UNIX
          case name if name.indexOf("aix") >= 0 => UNIX
          case name if name.indexOf("win") >= 0 => NTFS_FILESYSTEM
          case name if name.indexOf("mac") >= 0 => MACINTOSH
          case _                                => UNKNOWN
        }
    }
  }
  private val gzipInputCrcBytes = 4
  private val gzipInputSizeBytes = 4
  val gzipTrailerBytes: Int = gzipInputCrcBytes + gzipInputSizeBytes

  private val zeroByte: Byte = 0

  private val fileNameBytesSoftLimit =
    1024 // A limit is good practice. Actual limit will be max(chunk.size, soft limit). Typical maximum file size is 255 characters.
  private val fileCommentBytesSoftLimit =
    1024 * 1024 // A limit is good practice. Actual limit will be max(chunk.size, soft limit). 1 MiB feels reasonable for a comment.

  private def moveAsChunkBytes(values: Array[Byte]): Chunk[Byte] =
    moveAsChunkBytes(values, values.length)

  private def moveAsChunkBytes(values: Array[Byte], length: Int): Chunk[Byte] =
    if (length > 0) Chunk.array(values, 0, length)
    else Chunk.empty[Byte]

  private def unsignedToInt(lsb: Byte, msb: Byte): Int =
    ((msb & 0xff) << 8) | (lsb & 0xff)

  private def unsignedToLong(lsb: Byte, byte2: Byte, byte3: Byte, msb: Byte): Long =
    ((msb.toLong & 0xff) << 24) | ((byte3 & 0xff) << 16) | ((byte2 & 0xff) << 8) | (lsb & 0xff)

}