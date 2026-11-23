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

import cats.effect.IO
import cats.effect.LiftIO
import cats.effect.kernel.Sync

import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.zip._

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

}

private trait CompressionCompanionPlatformLowPriority { this: CompressionCompanionPlatform =>
  @deprecated("Add Compression constraint or use forSync", "3.7.0")
  implicit def implicitForSync[F[_]: Sync]: Compression[F] = forSync
}

private[compression] trait CompressionCompanionPlatform
    extends CompressionCompanionPlatformLowPriority {

  def forIO: Compression[IO] = forLiftIO

  implicit def forLiftIO[F[_]: Sync: LiftIO]: Compression[F] = {
    val _ = LiftIO[F]
    forSync
  }

  def forSync[F[_]](implicit F: Sync[F]): Compression[F] =
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
          Pull
            .bracketCase[F, Byte, Inflater, Unit](
              Pull.eval(F.delay(new Inflater(inflateParams.header.juzDeflaterNoWrap))),
              inflater => _inflate(inflateParams, inflater, crc32 = None)(stream).void,
              (inflater, _) => Pull.eval(F.delay(inflater.end()))
            )
            .stream

      private def _inflate(
          inflateParams: InflateParams,
          inflater: Inflater,
          crc32: Option[CRC32]
      ): Stream[F, Byte] => Pull[F, Byte, Stream[F, Byte]] =
        in =>
          Pull.suspend {
            val inflatedBuffer = new Array[Byte](inflateParams.bufferSizeOrMinimum)
            def go(stream: Stream[F, Byte]): Pull[F, Byte, Stream[F, Byte]] =
              if (inflater.finished()) Pull.pure(stream)
              else
                stream.pull.uncons.flatMap {
                  case Some((deflatedChunk, deflatedStream)) =>
                    _inflate_chunk(inflater, crc32, deflatedChunk, inflatedBuffer).flatMap {
                      leftover =>
                        if (inflater.finished())
                          if (leftover.isEmpty) Pull.pure(deflatedStream)
                          else Pull.pure(Stream.chunk(leftover) ++ deflatedStream)
                        else go(deflatedStream)
                    }
                  case None =>
                    if (!inflater.finished)
                      Pull.raiseError[F](new DataFormatException("Insufficient data"))
                    else Pull.pure(Stream.empty)
                }
            go(in)
          }

      private def _inflate_chunk(
          inflater: Inflater,
          crc32: Option[CRC32],
          chunk: Chunk[Byte],
          inflatedBuffer: Array[Byte]
      ): Pull[F, Byte, Chunk[Byte]] = {
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

        def leftoverChunk(): Chunk[Byte] = {
          val remaining = inflater.getRemaining
          if (remaining > 0)
            Chunk.array(
              bytesChunk.values,
              bytesChunk.offset + bytesChunk.length - remaining,
              remaining
            )
          else Chunk.empty[Byte]
        }

        def go(): Pull[F, Byte, Chunk[Byte]] =
          runInflate() match {
            case inflatedBytes if inflatedBytes <= -2 =>
              Pull.pure(leftoverChunk())
            case -1 =>
              Pull.pure(Chunk.empty[Byte])
            case inflatedBytes if inflatedBytes < inflatedBuffer.length =>
              Pull.output(copyAsChunkBytes(inflatedBuffer, inflatedBytes)) >> {
                if (inflater.finished()) Pull.pure(leftoverChunk())
                else Pull.pure(Chunk.empty[Byte])
              }
            case inflatedBytes =>
              Pull.output(copyAsChunkBytes(inflatedBuffer, inflatedBytes)) >> go()
          }

        go()
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
              Pull
                .bracketCase[F, GunzipResult[F], (Inflater, CRC32, CRC32), Unit](
                  Pull.eval(F.delay((new Inflater(true), new CRC32(), new CRC32()))),
                  { case (inflater, headerCrc32, contentCrc32) =>
                    _gunzip_readMemberHeader(stream, headerCrc32).flatMap {
                      case Left(err) =>
                        Pull.output1(GunzipResult(Stream.raiseError(err)))
                      case Right((parsed, afterHeader)) =>
                        val content: Stream[F, Byte] =
                          _gunzip_processMember(
                            afterHeader,
                            parsed.flags,
                            params,
                            inflater,
                            headerCrc32,
                            contentCrc32
                          ).stream
                        Pull.output1(
                          GunzipResult(
                            content,
                            modificationTime =
                              if (parsed.mtime != 0L) Some(Instant.ofEpochSecond(parsed.mtime))
                              else None,
                            fileName = parsed.fileName,
                            comment = parsed.comment
                          )
                        )
                    }
                  },
                  { case ((inflater, _, _), _) => Pull.eval(F.delay(inflater.end())) }
                )
                .stream
            case params: InflateParams =>
              Stream.raiseError(
                new ZipException(
                  s"${ZLibParams.Header.GZIP} header type required, not ${params.header}."
                )
              )
          }

      private case class ParsedGzipHeader(
          flags: Byte,
          mtime: Long,
          fileName: Option[String],
          comment: Option[String]
      )

      private def _gunzip_readMemberHeader(
          stream: Stream[F, Byte],
          headerCrc32: CRC32
      ): Pull[F, Nothing, Either[Throwable, (ParsedGzipHeader, Stream[F, Byte])]] =
        stream.pull.unconsN(gzipHeaderBytes).flatMap {
          case None =>
            Pull.pure(Left(new EOFException()))
          case Some((headerChunk, afterHeader)) =>
            val bytes = headerChunk.toArraySlice.compact.values
            if (
              bytes.length != gzipHeaderBytes ||
              bytes(0) != gzipMagicFirstByte ||
              bytes(1) != gzipMagicSecondByte
            )
              Pull.pure(Left(new ZipException("Not in gzip format")))
            else if (bytes(2) != gzipCompressionMethod.DEFLATE)
              Pull.pure(
                Left(new ZipException(s"Unsupported gzip compression method: ${bytes(2)}"))
              )
            else {
              val flags = bytes(3)
              val reservedErr: Option[String] =
                if (gzipFlag.reserved5(flags))
                  Some("Unsupported gzip flag reserved bit 5 is non-zero")
                else if (gzipFlag.reserved6(flags))
                  Some("Unsupported gzip flag reserved bit 6 is non-zero")
                else if (gzipFlag.reserved7(flags))
                  Some("Unsupported gzip flag reserved bit 7 is non-zero")
                else None
              reservedErr match {
                case Some(msg) => Pull.pure(Left(new ZipException(msg)))
                case None      =>
                  headerCrc32.update(bytes)
                  val mtime = unsignedToLong(bytes(4), bytes(5), bytes(6), bytes(7))
                  _gunzip_skipExtraField(afterHeader, gzipFlag.fextra(flags), headerCrc32)
                    .flatMap { s1 =>
                      _gunzip_readStringField(
                        s1,
                        gzipFlag.fname(flags),
                        fileNameBytesSoftLimit,
                        headerCrc32,
                        "file name"
                      ).flatMap { case (fileName, s2) =>
                        _gunzip_readStringField(
                          s2,
                          gzipFlag.fcomment(flags),
                          fileCommentBytesSoftLimit,
                          headerCrc32,
                          "file comment"
                        ).map { case (comment, s3) =>
                          Right((ParsedGzipHeader(flags, mtime, fileName, comment), s3))
                        }
                      }
                    }
              }
            }
        }

      private def _gunzip_skipExtraField(
          stream: Stream[F, Byte],
          isPresent: Boolean,
          headerCrc32: CRC32
      ): Pull[F, Nothing, Stream[F, Byte]] =
        if (!isPresent) Pull.pure(stream)
        else
          stream.pull.unconsN(gzipOptionalExtraFieldLengthBytes).flatMap {
            case None =>
              Pull.raiseError[F](new EOFException())
            case Some((lenChunk, afterLen)) =>
              val lenBytes = lenChunk.toArraySlice.compact.values
              if (lenBytes.length != gzipOptionalExtraFieldLengthBytes)
                Pull.raiseError[F](
                  new ZipException("Failed to read optional extra field header length")
                )
              else {
                headerCrc32.update(lenBytes)
                val xlen = unsignedToInt(lenBytes(0), lenBytes(1))
                afterLen.pull.unconsN(xlen).flatMap {
                  case None =>
                    Pull.raiseError[F](
                      new ZipException("Failed to read optional extra field header")
                    )
                  case Some((extraChunk, afterExtra)) =>
                    val extraSlice = extraChunk.toArraySlice
                    headerCrc32.update(extraSlice.values, extraSlice.offset, extraSlice.length)
                    Pull.pure(afterExtra)
                }
              }
          }

      private def _gunzip_readStringField(
          stream: Stream[F, Byte],
          isPresent: Boolean,
          softLimit: Int,
          headerCrc32: CRC32,
          fieldName: String
      ): Pull[F, Nothing, (Option[String], Stream[F, Byte])] =
        if (!isPresent) Pull.pure((Option.empty[String], stream))
        else {
          def go(
              acc: List[Chunk[Byte]],
              accSize: Int,
              atLimit: Boolean,
              s: Stream[F, Byte]
          ): Pull[F, Nothing, (Chunk[Byte], Stream[F, Byte])] =
            s.pull.uncons.flatMap {
              case None =>
                Pull.raiseError[F](new ZipException(s"Failed to read $fieldName field"))
              case Some((chunk, rest)) =>
                chunk.indexWhere(_ == zeroByte) match {
                  case Some(i) =>
                    val (pfx, sfx) = chunk.splitAt(i)
                    val pfxSlice = pfx.toArraySlice
                    if (pfxSlice.length > 0)
                      headerCrc32.update(pfxSlice.values, pfxSlice.offset, pfxSlice.length)
                    headerCrc32.update(zeroByte.toInt)
                    val afterNull = sfx.drop(1)
                    val collected =
                      if (atLimit) Chunk.concat(acc.reverse)
                      else Chunk.concat((pfx :: acc).reverse)
                    val truncated =
                      if (collected.size > softLimit) collected.take(softLimit) else collected
                    Pull.pure((truncated, Stream.chunk(afterNull) ++ rest))
                  case None =>
                    val slice = chunk.toArraySlice
                    headerCrc32.update(slice.values, slice.offset, slice.length)
                    val newSize = accSize + chunk.size
                    if (atLimit || newSize >= softLimit)
                      go(acc, accSize, atLimit = true, rest)
                    else
                      go(chunk :: acc, newSize, atLimit = false, rest)
                }
            }
          go(Nil, 0, atLimit = false, stream).map { case (chunk, rest) =>
            val str =
              if (chunk.isEmpty) Some("")
              else {
                val slice = chunk.toArraySlice
                Some(
                  new String(
                    slice.values,
                    slice.offset,
                    slice.length,
                    StandardCharsets.ISO_8859_1
                  )
                )
              }
            (str, rest)
          }
        }

      private def _gunzip_validateHeaderCrc(
          stream: Stream[F, Byte],
          isPresent: Boolean,
          headerCrc32: CRC32
      ): Pull[F, Nothing, Stream[F, Byte]] =
        if (!isPresent) Pull.pure(stream)
        else
          stream.pull.unconsN(gzipHeaderCrcBytes).flatMap {
            case None =>
              Pull.raiseError[F](new ZipException("Failed to read header CRC"))
            case Some((crcChunk, afterCrc)) =>
              val expected = unsignedToInt(crcChunk(0), crcChunk(1))
              val actual = headerCrc32.getValue & 0xffff
              if (expected != actual)
                Pull.raiseError[F](new ZipException("Header failed CRC validation"))
              else Pull.pure(afterCrc)
          }

      private def _gunzip_validateMemberTrailer(
          stream: Stream[F, Byte],
          inflater: Inflater,
          contentCrc32: CRC32
      ): Pull[F, Nothing, Stream[F, Byte]] =
        stream.pull.unconsN(gzipTrailerBytes).flatMap {
          case None =>
            Pull.raiseError[F](new ZipException("Failed to read trailer (1)"))
          case Some((trailer, after)) =>
            val expectedCrc =
              unsignedToLong(trailer(0), trailer(1), trailer(2), trailer(3))
            val actualCrc = contentCrc32.getValue
            val expectedSize =
              unsignedToLong(trailer(4), trailer(5), trailer(6), trailer(7))
            val actualSize = inflater.getBytesWritten & 0xffffffffL
            if (expectedCrc != actualCrc)
              Pull.raiseError[F](new ZipException("Content failed CRC validation"))
            else if (expectedSize != actualSize)
              Pull.raiseError[F](new ZipException("Content failed size validation"))
            else Pull.pure(after)
        }

      private def _gunzip_processMember(
          stream: Stream[F, Byte],
          flags: Byte,
          inflateParams: InflateParams,
          inflater: Inflater,
          headerCrc32: CRC32,
          contentCrc32: CRC32
      ): Pull[F, Byte, Unit] =
        _gunzip_validateHeaderCrc(stream, gzipFlag.fhcrc(flags), headerCrc32).flatMap { afterFhcrc =>
          _inflate(inflateParams, inflater, Some(contentCrc32))(afterFhcrc).flatMap { afterInflate =>
            _gunzip_validateMemberTrailer(afterInflate, inflater, contentCrc32).flatMap {
              afterTrailer =>
                _gunzip_continueWithNextMember(
                  afterTrailer,
                  inflateParams,
                  inflater,
                  headerCrc32,
                  contentCrc32
                )
            }
          }
        }

      private def _gunzip_continueWithNextMember(
          stream: Stream[F, Byte],
          inflateParams: InflateParams,
          inflater: Inflater,
          headerCrc32: CRC32,
          contentCrc32: CRC32
      ): Pull[F, Byte, Unit] =
        stream.pull.uncons.flatMap {
          case None               => Pull.done
          case Some((next, rest)) =>
            Pull.eval(F.delay {
              inflater.reset()
              headerCrc32.reset()
              contentCrc32.reset()
            }) >>
              _gunzip_readMemberHeader(Stream.chunk(next) ++ rest, headerCrc32).flatMap {
                case Left(err)                    => Pull.raiseError[F](err)
                case Right((parsed, afterHeader)) =>
                  _gunzip_processMember(
                    afterHeader,
                    parsed.flags,
                    inflateParams,
                    inflater,
                    headerCrc32,
                    contentCrc32
                  )
              }
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
