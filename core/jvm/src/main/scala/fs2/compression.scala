package fs2

import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.zip._

import cats.effect.Sync

/** Provides utilities for compressing/decompressing byte streams. */
object compression {

  /**
    * Returns a `Pipe` that deflates (compresses) its input elements using
    * a `java.util.zip.Deflater` with the parameters `level`, `nowrap` and `strategy`.
    * @param level the compression level (0-9)
    * @param nowrap if true then use GZIP compatible compression
    * @param bufferSize size of the internal buffer that is used by the
    *                   compressor. Default size is 32 KB.
    * @param strategy compression strategy -- see `java.util.zip.Deflater` for details
    */
  def deflate[F[_]](
      level: Int = Deflater.DEFAULT_COMPRESSION,
      nowrap: Boolean = false,
      bufferSize: Int = 1024 * 32,
      strategy: Int = Deflater.DEFAULT_STRATEGY
  )(implicit SyncF: Sync[F]): Pipe[F, Byte, Byte] =
    stream =>
      Stream
        .bracket(
          SyncF.delay {
            val deflater = new Deflater(level, nowrap)
            deflater.setStrategy(strategy)
            deflater
          }
        )(deflater => SyncF.delay(deflater.end()))
        .flatMap(deflater => _deflate(deflater, bufferSize, crc32 = None)(stream))

  private def _deflate[F[_]](
      deflater: Deflater,
      bufferSize: Int,
      crc32: Option[CRC32]
  ): Pipe[F, Byte, Byte] =
    _deflate_stream(deflater, bufferSize.max(minBufferSize), crc32)(_).stream

  private def _deflate_chunk[F[_]](
      chunk: Chunk[Byte],
      deflater: Deflater,
      deflatedBufferSize: Int,
      isFinalChunk: Boolean,
      crc32: Option[CRC32]
  ): Pull[F, Byte, Unit] = {
    val bytesChunk = chunk.toBytes
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

    def deflateInto(deflatedBuffer: Array[Byte]): Int =
      if (isDone) 0 else deflater.deflate(deflatedBuffer)

    def pull(): Pull[F, Byte, Unit] = {
      val deflatedBuffer = new Array[Byte](deflatedBufferSize)
      val deflatedBytes = deflateInto(deflatedBuffer)
      if (isDone) {
        Pull.output(asChunkBytes(deflatedBuffer, deflatedBytes))
      } else {
        Pull.output(asChunkBytes(deflatedBuffer, deflatedBytes)) >> pull()
      }
    }

    pull()
  }

  private def _deflate_stream[F[_]](
      deflater: Deflater,
      bufferSize: Int,
      crc32: Option[CRC32]
  ): Stream[F, Byte] => Pull[F, Byte, Unit] =
    _.pull.unconsNonEmpty.flatMap {
      case Some((inflatedChunk, inflatedStream)) =>
        _deflate_chunk(inflatedChunk, deflater, bufferSize, isFinalChunk = false, crc32) >> _deflate_stream(
          deflater,
          bufferSize,
          crc32
        )(inflatedStream)
      case None =>
        _deflate_chunk(Chunk.empty[Byte], deflater, bufferSize, isFinalChunk = true, crc32)
    }

  /**
    * Returns a `Pipe` that inflates (decompresses) its input elements using
    * a `java.util.zip.Inflater` with the parameter `nowrap`.
    * @param nowrap if true then support GZIP compatible decompression
    * @param bufferSize size of the internal buffer that is used by the
    *                   decompressor. Default size is 32 KB.
    */
  def inflate[F[_]](nowrap: Boolean = false, bufferSize: Int = 1024 * 32)(
      implicit SyncF: Sync[F]
  ): Pipe[F, Byte, Byte] =
    stream =>
      Stream
        .bracket(SyncF.delay(new Inflater(nowrap)))(inflater => SyncF.delay(inflater.end()))
        .flatMap(inflater => _inflate(bufferSize, inflater, crc32 = None)(SyncF)(stream))

  private def _inflate[F[_]](bufferSize: Int, inflater: Inflater, crc32: Option[CRC32])(
      implicit SyncF: Sync[F]
  ): Pipe[F, Byte, Byte] =
    _.pull.unconsNonEmpty.flatMap {
      case Some((deflatedChunk, deflatedStream)) =>
        _inflate_chunk(deflatedChunk, inflater, bufferSize.max(minBufferSize), crc32) >> _inflate_stream(
          inflater,
          bufferSize.max(minBufferSize),
          crc32
        )(SyncF)(deflatedStream)
      case None =>
        Pull.done
    }.stream

  private def _inflate_chunk[F[_]](
      chunk: Chunk[Byte],
      inflater: Inflater,
      inflatedBufferSize: Int,
      crc32: Option[CRC32]
  ): Pull[F, Byte, Unit] = {
    val bytesChunk = chunk.toBytes
    inflater.setInput(
      bytesChunk.values,
      bytesChunk.offset,
      bytesChunk.length
    )
    def inflateInto(inflatedBuffer: Array[Byte]): Int =
      if (inflater.finished()) -2
      else if (inflater.needsInput()) -1
      else {
        val byteCount = inflater.inflate(inflatedBuffer)
        crc32.foreach(_.update(inflatedBuffer, 0, byteCount))
        byteCount
      }

    def pull(): Pull[F, Byte, Unit] = {
      val inflatedBuffer = new Array[Byte](inflatedBufferSize)

      inflateInto(inflatedBuffer) match {
        case inflatedBytes if inflatedBytes <= -2 =>
          Pull.output(chunk)
        case inflatedBytes if inflatedBytes == -1 =>
          Pull.done
        case inflatedBytes if inflatedBytes < inflatedBuffer.length =>
          if (inflater.finished()) {
            inflater.getRemaining match {
              case bytesRemaining if bytesRemaining > 0 =>
                Pull.output(asChunkBytes(inflatedBuffer, inflatedBytes)) >>
                  Pull.output(
                    Chunk.Bytes(
                      bytesChunk.values,
                      bytesChunk.offset + bytesChunk.length - bytesRemaining,
                      bytesRemaining
                    )
                  )
              case _ =>
                Pull.output(asChunkBytes(inflatedBuffer, inflatedBytes))
            }
          } else Pull.output(asChunkBytes(inflatedBuffer, inflatedBytes))
        case inflatedBytes =>
          Pull.output(asChunkBytes(inflatedBuffer, inflatedBytes)) >> pull()
      }
    }

    pull()
  }

  private def _inflate_stream[F[_]](
      inflater: Inflater,
      inflatedBufferSize: Int,
      crc32: Option[CRC32]
  )(implicit SyncF: Sync[F]): Stream[F, Byte] => Pull[F, Byte, Unit] =
    _.pull.unconsNonEmpty.flatMap {
      case Some((deflatedChunk, deflatedStream)) =>
        _inflate_chunk(deflatedChunk, inflater, inflatedBufferSize, crc32) >> _inflate_stream(
          inflater,
          inflatedBufferSize,
          crc32
        )(SyncF)(deflatedStream)
      case None =>
        if (!inflater.finished)
          Pull.raiseError[F](new DataFormatException("Insufficient data"))
        else {
          Pull.done
        }
    }

  /**
    * Returns a pipe that incrementally compresses input into the GZIP format
    * as defined by RFC 1952 at https://www.ietf.org/rfc/rfc1952.txt. Output is
    * compatible with the GNU utils `gunzip` utility, as well as really anything
    * else that understands GZIP. Note, however, that the GZIP format is not
    * "stable" in the sense that all compressors will produce identical output
    * given identical input. Part of the header seeding is arbitrary and chosen by
    * the compression implementation. For this reason, the exact bytes produced
    * by this pipe will differ in insignificant ways from the exact bytes produced
    * by a tool like the GNU utils `gzip`.
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
  def gzip[F[_]](
      bufferSize: Int = 1024 * 32,
      deflateLevel: Option[Int] = None,
      deflateStrategy: Option[Int] = None,
      modificationTime: Option[Instant] = None,
      fileName: Option[String] = None,
      comment: Option[String] = None
  )(implicit SyncF: Sync[F]): Pipe[F, Byte, Byte] =
    stream =>
      Stream
        .bracket(
          SyncF.delay {
            val deflater = new Deflater(deflateLevel.getOrElse(Deflater.DEFAULT_COMPRESSION), true)
            deflater.setStrategy(deflateStrategy.getOrElse(Deflater.DEFAULT_STRATEGY))
            (deflater, new CRC32())
          }
        ) { case (deflater, _) => SyncF.delay(deflater.end()) }
        .flatMap {
          case (deflater, crc32) =>
            _gzip_header(modificationTime, deflateLevel, fileName, comment) ++
              _deflate(deflater, bufferSize, Some(crc32))(stream) ++
              _gzip_trailer(deflater, crc32)
        }

  private def _gzip_header[F[_]](
      modificationTime: Option[Instant],
      deflateLevel: Option[Int],
      fileName: Option[String],
      comment: Option[String]
  ): Stream[F, Byte] = {
    // See RFC 1952: https://www.ietf.org/rfc/rfc1952.txt
    val secondsSince197001010000: Long =
      modificationTime.map(_.getEpochSecond).getOrElse(0)
    val header = Array[Byte](
      gzipMagicFirstByte, // ID1: Identification 1
      gzipMagicSecondByte, // ID2: Identification 2
      gzipCompressionMethod.DEFLATE, // CM: Compression Method
      (gzipFlag.FHCRC + // FLG: Header CRC
        fileName.map(_ => gzipFlag.FNAME).getOrElse(zeroByte) + // FLG: File name
        comment.map(_ => gzipFlag.FCOMMENT).getOrElse(zeroByte)).toByte, // FLG: Comment
      (secondsSince197001010000 & 0xff).toByte, // MTIME: Modification Time
      ((secondsSince197001010000 >> 8) & 0xff).toByte,
      ((secondsSince197001010000 >> 16) & 0xff).toByte,
      ((secondsSince197001010000 >> 24) & 0xff).toByte,
      deflateLevel match { // XFL: Extra flags
        case Some(Deflater.BEST_COMPRESSION) => gzipExtraFlag.DEFLATE_MAX_COMPRESSION_SLOWEST_ALGO
        case Some(Deflater.BEST_SPEED)       => gzipExtraFlag.DEFLATE_FASTEST_ALGO
        case _                               => zeroByte
      },
      gzipOperatingSystem.THIS
    ) // OS: Operating System
    val crc32 = new CRC32()
    crc32.update(header)
    val fileNameEncoded = fileName.map { string =>
      val bytes = string.replaceAll("\u0000", "_").getBytes(StandardCharsets.ISO_8859_1)
      crc32.update(bytes)
      crc32.update(zeroByte)
      bytes
    }
    val commentEncoded = comment.map { string =>
      val bytes = string.replaceAll("\u0000", " ").getBytes(StandardCharsets.ISO_8859_1)
      crc32.update(bytes)
      crc32.update(zeroByte)
      bytes
    }
    val crc32Value = crc32.getValue
    val crc16 = Array[Byte](
      (crc32Value & 0xff).toByte,
      ((crc32Value >> 8) & 0xff).toByte
    )
    Stream.chunk(asChunkBytes(header)) ++
      fileNameEncoded
        .map(bytes => Stream.chunk(asChunkBytes(bytes)) ++ Stream.emit(zeroByte))
        .getOrElse(Stream.empty) ++
      commentEncoded
        .map(bytes => Stream.chunk(asChunkBytes(bytes)) ++ Stream.emit(zeroByte))
        .getOrElse(Stream.empty) ++
      Stream.chunk(asChunkBytes(crc16))
  }

  private def _gzip_trailer[F[_]](deflater: Deflater, crc32: CRC32): Stream[F, Byte] = {
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
    Stream.chunk(asChunkBytes(trailer))
  }

  final case class GunzipResult[F[_]](
      content: Stream[F, Byte],
      modificationTime: Option[Instant] = None,
      fileName: Option[String] = None,
      comment: Option[String] = None
  )

  /**
    * Returns a pipe that incrementally decompresses input according to the GZIP
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
    * @return    Returns a stream containing a single GzipResult containing the
    *            properties of the content stream. Continue to stream the content
    *            by flat mapping it:
    *            stream
    *             .through(gunzip[IO]())
    *             .flatMap { gunzipResult =>
    *               // Access properties here.
    *               gunzipResult.content
    *             }
    */
  def gunzip[F[_]](
      bufferSize: Int = 1024 * 32
  )(
      implicit SyncF: Sync[F]
  ): Stream[F, Byte] => Stream[F, GunzipResult[F]] =
    stream =>
      Stream
        .bracket(SyncF.delay((new Inflater(true), new CRC32(), new CRC32()))) {
          case (inflater, _, _) => SyncF.delay(inflater.end())
        }
        .flatMap {
          case (inflater, headerCrc32, contentCrc32) =>
            stream.pull
              .unconsN(gzipHeaderBytes)
              .flatMap {
                case Some((mandatoryHeaderChunk, streamAfterMandatoryHeader)) =>
                  _gunzip_matchMandatoryHeader(
                    mandatoryHeaderChunk,
                    streamAfterMandatoryHeader,
                    headerCrc32,
                    contentCrc32,
                    bufferSize,
                    inflater
                  )
                case None =>
                  Pull.output1(GunzipResult(Stream.raiseError(new EOFException())))
              }
              .stream
        }

  private def _gunzip_matchMandatoryHeader[F[_]](
      mandatoryHeaderChunk: Chunk[Byte],
      streamAfterMandatoryHeader: Stream[F, Byte],
      headerCrc32: CRC32,
      contentCrc32: CRC32,
      bufferSize: Int,
      inflater: Inflater
  )(implicit SyncF: Sync[F]) =
    (mandatoryHeaderChunk.size, mandatoryHeaderChunk.toBytes.values) match {
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
          streamAfterMandatoryHeader,
          flags,
          headerCrc32,
          contentCrc32,
          secondsSince197001010000,
          bufferSize,
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

  private def _gunzip_readOptionalHeader[F[_]](
      streamAfterMandatoryHeader: Stream[F, Byte],
      flags: Byte,
      headerCrc32: CRC32,
      contentCrc32: CRC32,
      secondsSince197001010000: Long,
      bufferSize: Int,
      inflater: Inflater
  )(implicit SyncF: Sync[F]): Stream[F, GunzipResult[F]] =
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
      .flatMap {
        case (fileName, streamAfterFileName) =>
          streamAfterFileName
            .through(
              _gunzip_readOptionalStringField(
                gzipFlag.fcomment(flags),
                headerCrc32,
                "file comment",
                fileCommentBytesSoftLimit
              )
            )
            .flatMap {
              case (comment, streamAfterComment) =>
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
                          bufferSize = bufferSize,
                          inflater = inflater,
                          crc32 = Some(contentCrc32)
                        )
                      )
                      .through(_gunzip_validateTrailer(contentCrc32, inflater))
                  )
                )
            }
      }

  private def _gunzip_skipOptionalExtraField[F[_]](
      isPresent: Boolean,
      crc32: CRC32
  )(implicit Sync: Sync[F]): Pipe[F, Byte, Byte] =
    stream =>
      if (isPresent) {
        stream.pull
          .unconsN(gzipOptionalExtraFieldLengthBytes)
          .flatMap {
            case Some((optionalExtraFieldLengthChunk, streamAfterOptionalExtraFieldLength)) =>
              (optionalExtraFieldLengthChunk.size, optionalExtraFieldLengthChunk.toBytes.values) match {
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
                        val fieldBytes = optionalExtraFieldChunk.toBytes
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
      } else stream

  private def _gunzip_readOptionalStringField[F[_]](
      isPresent: Boolean,
      crc32: CRC32,
      fieldName: String,
      fieldBytesSoftLimit: Int
  )(
      implicit SyncF: Sync[F]
  ): Stream[F, Byte] => Stream[F, (Option[String], Stream[F, Byte])] =
    stream =>
      if (isPresent) {
        unconsUntil[F, Byte](_ == zeroByte, fieldBytesSoftLimit)(stream).flatMap {
          case Some((chunk, rest)) =>
            Pull.output1(
              (
                if (chunk.isEmpty) {
                  Some("")
                } else {
                  val bytesChunk = chunk.toBytes
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
                    crc32.update(byte)
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
        }.stream
      } else Stream.emit((Option.empty[String], stream))

  private def _gunzip_validateHeader[F[_]](
      isPresent: Boolean,
      crc32: CRC32
  )(implicit SyncF: Sync[F]): Pipe[F, Byte, Byte] =
    stream =>
      if (isPresent) {
        stream.pull
          .unconsN(gzipHeaderCrcBytes)
          .flatMap {
            case Some((headerCrcChunk, streamAfterHeaderCrc)) =>
              val expectedHeaderCrc16 = unsignedToInt(headerCrcChunk(0), headerCrcChunk(1))
              val actualHeaderCrc16 = crc32.getValue & 0xffff
              if (expectedHeaderCrc16 != actualHeaderCrc16) {
                Pull.raiseError(new ZipException("Header failed CRC validation"))
              } else {
                Pull.output1(streamAfterHeaderCrc)
              }
            case None =>
              Pull.raiseError(new ZipException("Failed to read header CRC"))
          }
          .stream
          .flatten
      } else stream

  private def _gunzip_validateTrailer[F[_]](
      crc32: CRC32,
      inflater: Inflater
  )(implicit SyncF: Sync[F]): Pipe[F, Byte, Byte] =
    stream =>
      {

        def validateTrailer(trailerChunk: Chunk[Byte]): Pull[F, Byte, Unit] =
          if (trailerChunk.size == gzipTrailerBytes) {
            val expectedInputCrc32 =
              unsignedToLong(trailerChunk(0), trailerChunk(1), trailerChunk(2), trailerChunk(3))
            val actualInputCrc32 = crc32.getValue
            val expectedInputSize =
              unsignedToLong(trailerChunk(4), trailerChunk(5), trailerChunk(6), trailerChunk(7))
            val actualInputSize = inflater.getBytesWritten & 0xFFFFFFFFL
            if (expectedInputCrc32 != actualInputCrc32) {
              Pull.raiseError(new ZipException("Content failed CRC validation"))
            } else if (expectedInputSize != actualInputSize) {
              Pull.raiseError(new ZipException("Content failed size validation"))
            } else {
              Pull.done
            }
          } else Pull.raiseError(new ZipException("Failed to read trailer (1)"))

        def streamUntilTrailer(last: Chunk[Byte]): Stream[F, Byte] => Pull[F, Byte, Unit] =
          _.pull.unconsNonEmpty
            .flatMap {
              case Some((next, rest)) =>
                if (next.size >= gzipTrailerBytes) {
                  Pull.output(last) >> streamUntilTrailer(next)(rest)
                } else {
                  streamUntilTrailer(Chunk.concatBytes(List(last.toBytes, next.toBytes)))(rest)
                }
              case None =>
                val preTrailerBytes = last.size - gzipTrailerBytes
                if (preTrailerBytes > 0) {
                  Pull.output(last.take(preTrailerBytes)) >> validateTrailer(
                    last.drop(preTrailerBytes)
                  )
                } else {
                  validateTrailer(last)
                }
            }

        stream.pull.unconsNonEmpty
          .flatMap {
            case Some((chunk, rest)) => streamUntilTrailer(chunk)(rest)
            case None                => Pull.raiseError(new ZipException("Failed to read trailer (2)"))
          }
      }.stream

  /**
    * Like Stream.unconsN, but returns a chunk of elements that do not satisfy the predicate, splitting chunk as necessary.
    * Elements will not be dropped after the soft limit is breached.
    *
    * `Pull.pure(None)` is returned if the end of the source stream is reached.
    */
  private def unconsUntil[F[_], O](
      predicate: O => Boolean,
      softLimit: Int
  ): Stream[F, O] => Pull[F, INothing, Option[(Chunk[O], Stream[F, O])]] = stream => {
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

  private val minBufferSize = 128

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

  private val fileNameBytesSoftLimit = 1024 // A limit is good practice. Actual limit will be max(chunk.size, soft limit). Typical maximum file size is 255 characters.
  private val fileCommentBytesSoftLimit =
    1024 * 1024 // A limit is good practice. Actual limit will be max(chunk.size, soft limit). 1 MiB feels reasonable for a comment.

  private def asChunkBytes(values: Array[Byte]): Chunk[Byte] =
    asChunkBytes(values, values.length)

  private def asChunkBytes(values: Array[Byte], length: Int): Chunk[Byte] =
    if (length > 0) Chunk.Bytes(values, 0, length) else Chunk.empty[Byte]

  private def unsignedToInt(lsb: Byte, msb: Byte): Int =
    ((msb & 0xff) << 8) | (lsb & 0xff)

  private def unsignedToLong(lsb: Byte, byte2: Byte, byte3: Byte, msb: Byte): Long =
    ((msb.toLong & 0xff) << 24) | ((byte3 & 0xff) << 16) | ((byte2 & 0xff) << 8) | (lsb & 0xff)

  @deprecated("No longer required", "2020-02-05")
  final case class NonProgressiveDecompressionException(bufferSize: Int)
      extends RuntimeException(s"buffer size $bufferSize is too small; gunzip cannot make progress")

}
