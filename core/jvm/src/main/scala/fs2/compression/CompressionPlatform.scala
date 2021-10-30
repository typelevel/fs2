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

import cats.effect.kernel.Sync

import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.zip._
import scala.reflect.ClassTag

private[compression] trait CompressionPlatform[F[_]] { self: Compression[F] =>

  /** Returns a pipe that incrementally compresses input into the GZIP format
    * as defined by RFC 1952 at https://www.ietf.org/rfc/rfc1952.txt. Output is
    * compatible with the GNU utils `gunzip` utility, as well as really anything
    * else that understands GZIP. Note, however, that the GZIP format is not
    * "stable" in the sense that all compressors will produce identical output
    * given identical input. Part of the header seeding is arbitrary and chosen by
    * the compression implementation. For this reason, the exact bytes produced
    * by this pipe will differ in insignificant ways from the exact bytes produced
    * by a tool like the GNU utils `gzip`.
    *
    * GZIP wraps a deflate stream with file attributes and stream integrity validation.
    * Therefore, GZIP is a good choice for compressing finite, complete, readily-available,
    * continuous or file streams. A simpler deflate stream may be better suited to
    * real-time, intermittent, fragmented, interactive or discontinuous streams where
    * network protocols typically provide stream integrity validation.
    *
    * @param bufferSize The buffer size which will be used to page data
    *                   into chunks. This will be the chunk size of the
    *                   output stream. You should set it to be equal to
    *                   the size of the largest chunk in the input stream.
    *                   Setting this to a size which is ''smaller'' than
    *                   the chunks in the input stream will result in
    *                   performance degradation of roughly 50-75%. Default
    *                   size is 32 KB.
    * @param deflateLevel     level the compression level (0-9)
    * @param deflateStrategy  strategy compression strategy -- see `java.util.zip.Deflater` for details
    * @param modificationTime optional file modification time
    * @param fileName         optional file name
    * @param comment          optional file comment
    */
  def gzip(
      bufferSize: Int = 1024 * 32,
      deflateLevel: Option[Int] = None,
      deflateStrategy: Option[Int] = None,
      modificationTime: Option[Instant] = None,
      fileName: Option[String] = None,
      comment: Option[String] = None
  ): Pipe[F, Byte, Byte] =
    gzip(
      fileName = fileName,
      modificationTime = modificationTime,
      comment = comment,
      deflateParams = DeflateParams(
        bufferSize = bufferSize,
        header = ZLibParams.Header.GZIP,
        level = deflateLevel
          .map(DeflateParams.Level.apply)
          .getOrElse(DeflateParams.Level.DEFAULT),
        strategy = deflateStrategy
          .map(DeflateParams.Strategy.apply)
          .getOrElse(DeflateParams.Strategy.DEFAULT),
        flushMode = DeflateParams.FlushMode.DEFAULT
      )
    )

  /** Returns a pipe that incrementally compresses input into the GZIP format
    * as defined by RFC 1952 at https://www.ietf.org/rfc/rfc1952.txt. Output is
    * compatible with the GNU utils `gunzip` utility, as well as really anything
    * else that understands GZIP. Note, however, that the GZIP format is not
    * "stable" in the sense that all compressors will produce identical output
    * given identical input. Part of the header seeding is arbitrary and chosen by
    * the compression implementation. For this reason, the exact bytes produced
    * by this pipe will differ in insignificant ways from the exact bytes produced
    * by a tool like the GNU utils `gzip`.
    *
    * GZIP wraps a deflate stream with file attributes and stream integrity validation.
    * Therefore, GZIP is a good choice for compressing finite, complete, readily-available,
    * continuous or file streams. A simpler deflate stream may be better suited to
    * real-time, intermittent, fragmented, interactive or discontinuous streams where
    * network protocols typically provide stream integrity validation.
    *
    * @param fileName         optional file name
    * @param modificationTime optional file modification time
    * @param comment          optional file comment
    * @param deflateParams    see [[compression.DeflateParams]]
    */
  def gzip(
      fileName: Option[String],
      modificationTime: Option[Instant],
      comment: Option[String],
      deflateParams: DeflateParams
  ): Pipe[F, Byte, Byte]

  /** Returns a pipe that incrementally decompresses input according to the GZIP
    * format as defined by RFC 1952 at https://www.ietf.org/rfc/rfc1952.txt. Any
    * errors in decompression will be sequenced as exceptions into the output
    * stream. Decompression is handled in a streaming and async fashion without
    * any thread blockage.
    *
    * The chunk size here is actually really important. Matching the input stream
    * largest chunk size, or roughly 8 KB (whichever is larger) is a good rule of
    * thumb.
    *
    * @param bufferSize The bounding size of the input buffer. This should roughly
    *                   match the size of the largest chunk in the input stream.
    *                   This will also be the chunk size in the output stream.
    *                    Default size is 32 KB.
    * @return See [[compression.GunzipResult]]
    */
  def gunzip(bufferSize: Int = 1024 * 32): Stream[F, Byte] => Stream[F, GunzipResult[F]] =
    gunzip(
      InflateParams(
        bufferSize = bufferSize,
        header = ZLibParams.Header.GZIP
      )
    )

  /** Returns a pipe that incrementally decompresses input according to the GZIP
    * format as defined by RFC 1952 at https://www.ietf.org/rfc/rfc1952.txt. Any
    * errors in decompression will be sequenced as exceptions into the output
    * stream. Decompression is handled in a streaming and async fashion without
    * any thread blockage.
    *
    * The chunk size here is actually really important. Matching the input stream
    * largest chunk size, or roughly 8 KB (whichever is larger) is a good rule of
    * thumb.
    *
    * @param inflateParams See [[compression.InflateParams]]
    * @return See [[compression.GunzipResult]]
    */
  def gunzip(inflateParams: InflateParams): Stream[F, Byte] => Stream[F, GunzipResult[F]]
}

private[compression] trait CompressionCompanionPlatform {

  implicit def forSync[F[_]](implicit F: Sync[F]): Compression[F] =
    new Compression.UnsealedCompression[F] {

      def deflate(deflateParams: DeflateParams): Pipe[F, Byte, Byte] =
        stream =>
          Stream
            .bracket(
              F.delay {
                val deflater =
                  new Deflater(
                    deflateParams.level.juzDeflaterLevel,
                    deflateParams.header.juzDeflaterNoWrap
                  )
                deflater.setStrategy(deflateParams.strategy.juzDeflaterStrategy)
                deflater
              }
            )(deflater => F.delay(deflater.end()))
            .flatMap(deflater => _deflate(deflateParams, deflater, crc32 = None)(stream))

      private def _deflate(
          deflateParams: DeflateParams,
          deflater: Deflater,
          crc32: Option[CRC32]
      ): Pipe[F, Byte, Byte] =
        in =>
          Stream.suspend {
            val deflatedBuffer = new Array[Byte](deflateParams.bufferSizeOrMinimum)
            _deflate_stream(deflateParams, deflater, crc32, deflatedBuffer)(in).stream
          }

      private def _deflate_chunk(
          deflateParams: DeflateParams,
          deflater: Deflater,
          crc32: Option[CRC32],
          chunk: Chunk[Byte],
          deflatedBuffer: Array[Byte],
          isFinalChunk: Boolean
      ): Pull[F, Byte, Unit] = {
        val bytesChunk = chunk.toArraySlice
        deflater.setInput(
          bytesChunk.values,
          bytesChunk.offset,
          bytesChunk.length
        )
        if (isFinalChunk)
          deflater.finish()
        crc32.foreach(_.update(bytesChunk.values, bytesChunk.offset, bytesChunk.length))

        def isDone: Boolean =
          (isFinalChunk && deflater.finished) || (!isFinalChunk && deflater.needsInput)

        def runDeflate(): Int =
          if (isDone) 0
          else
            deflater.deflate(
              deflatedBuffer,
              0,
              deflateParams.bufferSizeOrMinimum,
              deflateParams.flushMode.juzDeflaterFlushMode
            )

        def pull(): Pull[F, Byte, Unit] = {
          val deflatedBytes = runDeflate()
          if (isDone)
            Pull.output(copyAsChunkBytes(deflatedBuffer, deflatedBytes))
          else
            Pull.output(copyAsChunkBytes(deflatedBuffer, deflatedBytes)) >> pull()
        }

        pull()
      }

      private def _deflate_stream(
          deflateParams: DeflateParams,
          deflater: Deflater,
          crc32: Option[CRC32],
          deflatedBuffer: Array[Byte]
      ): Stream[F, Byte] => Pull[F, Byte, Unit] =
        _.pull.uncons.flatMap {
          case Some((inflatedChunk, inflatedStream)) =>
            _deflate_chunk(
              deflateParams,
              deflater,
              crc32,
              inflatedChunk,
              deflatedBuffer,
              isFinalChunk = false
            ) >>
              _deflate_stream(deflateParams, deflater, crc32, deflatedBuffer)(inflatedStream)
          case None =>
            _deflate_chunk(
              deflateParams,
              deflater,
              crc32,
              Chunk.empty[Byte],
              deflatedBuffer,
              isFinalChunk = true
            )
        }

      /** Returns a `Pipe` that inflates (decompresses) its input elements using
        * a `java.util.zip.Inflater` with the parameter `nowrap`.
        * @param inflateParams See [[compression.InflateParams]]
        */
      def inflate(inflateParams: InflateParams): Pipe[F, Byte, Byte] =
        stream =>
          Stream
            .bracket(F.delay(new Inflater(inflateParams.header.juzDeflaterNoWrap)))(inflater =>
              F.delay(inflater.end())
            )
            .flatMap(inflater => _inflate(inflateParams, inflater, crc32 = None)(stream))

      private def _inflate(
          inflateParams: InflateParams,
          inflater: Inflater,
          crc32: Option[CRC32]
      ): Pipe[F, Byte, Byte] =
        in =>
          Stream.suspend {
            val inflatedBuffer = new Array[Byte](inflateParams.bufferSizeOrMinimum)
            in.pull.uncons.flatMap {
              case Some((deflatedChunk, deflatedStream)) =>
                _inflate_chunk(
                  inflater,
                  crc32,
                  deflatedChunk,
                  inflatedBuffer
                ) >> _inflate_stream(
                  inflateParams,
                  inflater,
                  crc32,
                  inflatedBuffer
                )(deflatedStream)
              case None =>
                Pull.done
            }.stream
          }

      private def _inflate_chunk(
          inflater: Inflater,
          crc32: Option[CRC32],
          chunk: Chunk[Byte],
          inflatedBuffer: Array[Byte]
      ): Pull[F, Byte, Unit] = {
        val bytesChunk = chunk.toArraySlice
        inflater.setInput(
          bytesChunk.values,
          bytesChunk.offset,
          bytesChunk.length
        )
        def runInflate(): Int =
          if (inflater.finished()) -2
          else if (inflater.needsInput()) -1
          else {
            val byteCount = inflater.inflate(inflatedBuffer)
            crc32.foreach(_.update(inflatedBuffer, 0, byteCount))
            byteCount
          }

        def pull(): Pull[F, Byte, Unit] =
          runInflate() match {
            case inflatedBytes if inflatedBytes <= -2 =>
              inflater.getRemaining match {
                case bytesRemaining if bytesRemaining > 0 =>
                  Pull.output(
                    Chunk.array(
                      bytesChunk.values,
                      bytesChunk.offset + bytesChunk.length - bytesRemaining,
                      bytesRemaining
                    )
                  )
                case _ =>
                  Pull.done
              }
            case inflatedBytes if inflatedBytes == -1 =>
              Pull.done
            case inflatedBytes if inflatedBytes < inflatedBuffer.length =>
              if (inflater.finished())
                inflater.getRemaining match {
                  case bytesRemaining if bytesRemaining > 0 =>
                    Pull.output(copyAsChunkBytes(inflatedBuffer, inflatedBytes)) >>
                      Pull.output(
                        Chunk.array(
                          bytesChunk.values,
                          bytesChunk.offset + bytesChunk.length - bytesRemaining,
                          bytesRemaining
                        )
                      )
                  case _ =>
                    Pull.output(copyAsChunkBytes(inflatedBuffer, inflatedBytes))
                }
              else Pull.output(copyAsChunkBytes(inflatedBuffer, inflatedBytes))
            case inflatedBytes =>
              Pull.output(copyAsChunkBytes(inflatedBuffer, inflatedBytes)) >> pull()
          }

        pull()
      }

      private def _inflate_stream(
          inflateParams: InflateParams,
          inflater: Inflater,
          crc32: Option[CRC32],
          inflatedBuffer: Array[Byte]
      ): Stream[F, Byte] => Pull[F, Byte, Unit] =
        _.pull.uncons.flatMap {
          case Some((deflatedChunk, deflatedStream)) =>
            _inflate_chunk(
              inflater,
              crc32,
              deflatedChunk,
              inflatedBuffer
            ) >> _inflate_stream(
              inflateParams,
              inflater,
              crc32,
              inflatedBuffer
            )(deflatedStream)
          case None =>
            if (!inflater.finished)
              Pull.raiseError[F](new DataFormatException("Insufficient data"))
            else
              Pull.done
        }

      def gzip(
          fileName: Option[String],
          modificationTime: Option[Instant],
          comment: Option[String],
          deflateParams: DeflateParams
      ): Pipe[F, Byte, Byte] =
        stream =>
          deflateParams match {
            case params: DeflateParams if params.header == ZLibParams.Header.GZIP =>
              Stream
                .bracket(
                  F.delay {
                    val deflater = new Deflater(params.level.juzDeflaterLevel, true)
                    deflater.setStrategy(params.strategy.juzDeflaterStrategy)
                    (deflater, new CRC32())
                  }
                ) { case (deflater, _) => F.delay(deflater.end()) }
                .flatMap { case (deflater, crc32) =>
                  _gzip_header(
                    fileName,
                    modificationTime,
                    comment,
                    params.level.juzDeflaterLevel,
                    params.fhCrcEnabled
                  ) ++
                    _deflate(
                      params,
                      deflater,
                      Some(crc32)
                    )(stream) ++
                    _gzip_trailer(deflater, crc32)
                }
            case params: DeflateParams =>
              Stream.raiseError(
                new ZipException(
                  s"${ZLibParams.Header.GZIP} header type required, not ${params.header}."
                )
              )
          }

      private def _gzip_header(
          fileName: Option[String],
          modificationTime: Option[Instant],
          comment: Option[String],
          deflateLevel: Int,
          fhCrcEnabled: Boolean
      ): Stream[F, Byte] = {
        // See RFC 1952: https://www.ietf.org/rfc/rfc1952.txt
        val secondsSince197001010000: Long =
          modificationTime.map(_.getEpochSecond).getOrElse(0)
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
            case Deflater.BEST_COMPRESSION => gzipExtraFlag.DEFLATE_MAX_COMPRESSION_SLOWEST_ALGO
            case Deflater.BEST_SPEED       => gzipExtraFlag.DEFLATE_FASTEST_ALGO
            case _                         => zeroByte
          },
          gzipOperatingSystem.THIS
        ) // OS: Operating System
        val crc32 = new CRC32()
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
        val crc32Value = crc32.getValue

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

      private def _gzip_trailer(deflater: Deflater, crc32: CRC32): Stream[F, Byte] = {
        // See RFC 1952: https://www.ietf.org/rfc/rfc1952.txt
        val crc32Value = crc32.getValue
        val bytesIn = deflater.getTotalIn
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

      def gunzip(inflateParams: InflateParams): Stream[F, Byte] => Stream[F, GunzipResult[F]] =
        stream =>
          inflateParams match {
            case params: InflateParams if params.header == ZLibParams.Header.GZIP =>
              Stream
                .bracket(F.delay((new Inflater(true), new CRC32(), new CRC32()))) {
                  case (inflater, _, _) => F.delay(inflater.end())
                }
                .flatMap { case (inflater, headerCrc32, contentCrc32) =>
                  stream.pull
                    .unconsN(gzipHeaderBytes)
                    .flatMap {
                      case Some((mandatoryHeaderChunk, streamAfterMandatoryHeader)) =>
                        _gunzip_matchMandatoryHeader(
                          params,
                          mandatoryHeaderChunk,
                          streamAfterMandatoryHeader,
                          headerCrc32,
                          contentCrc32,
                          inflater
                        )
                      case None =>
                        Pull.output1(GunzipResult(Stream.raiseError(new EOFException())))
                    }
                    .stream
                }
            case params: InflateParams =>
              Stream.raiseError(
                new ZipException(
                  s"${ZLibParams.Header.GZIP} header type required, not ${params.header}."
                )
              )
          }

      private def _gunzip_matchMandatoryHeader(
          inflateParams: InflateParams,
          mandatoryHeaderChunk: Chunk[Byte],
          streamAfterMandatoryHeader: Stream[F, Byte],
          headerCrc32: CRC32,
          contentCrc32: CRC32,
          inflater: Inflater
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
              GunzipResult(
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
              GunzipResult(
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
              GunzipResult(
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
              inflateParams,
              streamAfterMandatoryHeader,
              flags,
              headerCrc32,
              contentCrc32,
              secondsSince197001010000,
              inflater
            ).pull.uncons1
              .flatMap {
                case Some((gunzipResult, _)) =>
                  Pull.output1(gunzipResult)
                case None =>
                  Pull.output1(GunzipResult(Stream.raiseError(new EOFException())))
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
              GunzipResult(
                Stream.raiseError(
                  new ZipException(
                    s"Unsupported gzip compression method: $compressionMethod"
                  )
                )
              )
            )
          case _ =>
            Pull.output1(
              GunzipResult(Stream.raiseError(new ZipException("Not in gzip format")))
            )
        }

      private def _gunzip_readOptionalHeader(
          inflateParams: InflateParams,
          streamAfterMandatoryHeader: Stream[F, Byte],
          flags: Byte,
          headerCrc32: CRC32,
          contentCrc32: CRC32,
          secondsSince197001010000: Long,
          inflater: Inflater
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
                  GunzipResult(
                    modificationTime =
                      if (secondsSince197001010000 != 0)
                        Some(Instant.ofEpochSecond(secondsSince197001010000))
                      else None,
                    fileName = fileName,
                    comment = comment,
                    content = streamAfterComment
                      .through(
                        _gunzip_validateHeader(
                          (flags & gzipFlag.FHCRC) == gzipFlag.FHCRC,
                          headerCrc32
                        )
                      )
                      .through(
                        _inflate(
                          inflateParams = inflateParams,
                          inflater = inflater,
                          crc32 = Some(contentCrc32)
                        )
                      )
                      .through(_gunzip_validateTrailer(contentCrc32, inflater))
                  )
                )
              }
          }

      private def _gunzip_skipOptionalExtraField(
          isPresent: Boolean,
          crc32: CRC32
      ): Pipe[F, Byte, Byte] =
        stream =>
          if (isPresent)
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
                      crc32.update(lengthBytes)
                      val optionalExtraFieldLength = unsignedToInt(firstByte, secondByte)
                      streamAfterOptionalExtraFieldLength.pull
                        .unconsN(optionalExtraFieldLength)
                        .flatMap {
                          case Some((optionalExtraFieldChunk, streamAfterOptionalExtraField)) =>
                            val fieldBytes = optionalExtraFieldChunk.toArraySlice
                            crc32.update(fieldBytes.values, fieldBytes.offset, fieldBytes.length)
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
          else stream

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
                        .dropWhile { byte =>
                          // Will also call crc32.update(byte) for the zeroByte dropped hereafter.
                          crc32.update(byte.toInt)
                          byte != zeroByte
                        }
                        .drop(1)
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
          crc32: CRC32
      ): Pipe[F, Byte, Byte] =
        stream =>
          if (isPresent)
            stream.pull
              .unconsN(gzipHeaderCrcBytes)
              .flatMap {
                case Some((headerCrcChunk, streamAfterHeaderCrc)) =>
                  val expectedHeaderCrc16 = unsignedToInt(headerCrcChunk(0), headerCrcChunk(1))
                  val actualHeaderCrc16 = crc32.getValue & 0xffff
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
          crc32: CRC32,
          inflater: Inflater
      ): Pipe[F, Byte, Byte] =
        stream =>
          {

            def validateTrailer(trailerChunk: Chunk[Byte]): Pull[F, Byte, Unit] =
              if (trailerChunk.size == gzipTrailerBytes) {
                val expectedInputCrc32 =
                  unsignedToLong(trailerChunk(0), trailerChunk(1), trailerChunk(2), trailerChunk(3))
                val actualInputCrc32 = crc32.getValue
                val expectedInputSize =
                  unsignedToLong(trailerChunk(4), trailerChunk(5), trailerChunk(6), trailerChunk(7))
                val actualInputSize = inflater.getBytesWritten & 0xffffffffL
                if (expectedInputCrc32 != actualInputCrc32)
                  Pull.raiseError(new ZipException("Content failed CRC validation"))
                else if (expectedInputSize != actualInputSize)
                  Pull.raiseError(new ZipException("Content failed size validation"))
                else
                  Pull.done
              } else Pull.raiseError(new ZipException("Failed to read trailer (1)"))

            def streamUntilTrailer(last: Chunk[Byte]): Stream[F, Byte] => Pull[F, Byte, Unit] =
              _.pull.uncons
                .flatMap {
                  case Some((next, rest)) =>
                    if (inflater.finished())
                      if (next.size >= gzipTrailerBytes)
                        if (last.nonEmpty) Pull.output(last) >> streamUntilTrailer(next)(rest)
                        else streamUntilTrailer(next)(rest)
                      else
                        streamUntilTrailer(last ++ next)(rest)
                    else if (last.nonEmpty)
                      Pull.output(last) >> Pull.output(next) >>
                        streamUntilTrailer(Chunk.empty[Byte])(rest)
                    else Pull.output(next) >> streamUntilTrailer(Chunk.empty[Byte])(rest)
                  case None =>
                    val preTrailerBytes = last.size - gzipTrailerBytes
                    if (preTrailerBytes > 0)
                      Pull.output(last.take(preTrailerBytes)) >>
                        validateTrailer(last.drop(preTrailerBytes))
                    else
                      validateTrailer(last)
                }

            streamUntilTrailer(Chunk.empty[Byte])(stream)
          }.stream

      /** Like Stream.unconsN, but returns a chunk of elements that do not satisfy the predicate, splitting chunk as necessary.
        * Elements will not be dropped after the soft limit is breached.
        *
        * `Pull.pure(None)` is returned if the end of the source stream is reached.
        */
      private def unconsUntil[O: ClassTag](
          predicate: O => Boolean,
          softLimit: Int
      ): Stream[F, O] => Pull[F, INothing, Option[(Chunk[O], Stream[F, O])]] =
        stream => {
          def go(
              acc: List[Chunk[O]],
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
                    Pull.pure(Some(Chunk.concat((pfx :: acc).reverse) -> tl.cons(sfx)))
                  case None =>
                    val newSize = size + hd.size
                    if (newSize < softLimit) go(hd :: acc, tl, newSize)
                    else Pull.pure(Some(Chunk.concat((hd :: acc).reverse) -> tl))
                }
            }

          go(Nil, stream)
        }

      private val gzipHeaderBytes = 10
      private val gzipMagicFirstByte: Byte = 0x1f.toByte
      private val gzipMagicSecondByte: Byte = 0x8b.toByte
      private object gzipCompressionMethod {
        val DEFLATE: Byte = Deflater.DEFLATED.toByte
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

        val THIS: Byte = System.getProperty("os.name").toLowerCase() match {
          case name if name.indexOf("nux") > 0  => UNIX
          case name if name.indexOf("nix") > 0  => UNIX
          case name if name.indexOf("aix") >= 0 => UNIX
          case name if name.indexOf("win") >= 0 => NTFS_FILESYSTEM
          case name if name.indexOf("mac") >= 0 => MACINTOSH
          case _                                => UNKNOWN
        }
      }
      private val gzipInputCrcBytes = 4
      private val gzipInputSizeBytes = 4
      private val gzipTrailerBytes = gzipInputCrcBytes + gzipInputSizeBytes

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

      private def copyAsChunkBytes(values: Array[Byte], length: Int): Chunk[Byte] =
        if (length > 0) {
          val target = new Array[Byte](length)
          System.arraycopy(values, 0, target, 0, length)
          Chunk.array(target, 0, length)
        } else Chunk.empty[Byte]

      private def unsignedToInt(lsb: Byte, msb: Byte): Int =
        ((msb & 0xff) << 8) | (lsb & 0xff)

      private def unsignedToLong(lsb: Byte, byte2: Byte, byte3: Byte, msb: Byte): Long =
        ((msb.toLong & 0xff) << 24) | ((byte3 & 0xff) << 16) | ((byte2 & 0xff) << 8) | (lsb & 0xff)
    }
}
