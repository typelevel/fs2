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

import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.zip._

import cats.effect.Sync

/** Provides utilities for compressing/decompressing byte streams. */
object compression {

  object ZLibParams {

    sealed abstract class Header(private[compression] val juzDeflaterNoWrap: Boolean)
    case object Header {
      private[fs2] def apply(juzDeflaterNoWrap: Boolean): ZLibParams.Header =
        if (juzDeflaterNoWrap) ZLibParams.Header.GZIP else ZLibParams.Header.ZLIB

      case object ZLIB extends Header(juzDeflaterNoWrap = false)
      case object GZIP extends Header(juzDeflaterNoWrap = true)
    }
  }

  /**
    * Deflate algorithm parameters.
    */
  sealed trait DeflateParams {

    /**
      * Size of the internal buffer. Default size is 32 KB.
      */
    val bufferSize: Int

    /**
      * Compression header. Defaults to [[ZLibParams.Header.ZLIB]].
      */
    val header: ZLibParams.Header

    /**
      * Compression level. Default level is [[java.util.zip.Deflater.DEFAULT_COMPRESSION]].
      */
    val level: DeflateParams.Level

    /**
      * Compression strategy. Default strategy is [[java.util.zip.Deflater.DEFAULT_STRATEGY]].
      */
    val strategy: DeflateParams.Strategy

    /**
      * Compression flush mode. Default flush mode is [[java.util.zip.Deflater.NO_FLUSH]].
      */
    val flushMode: DeflateParams.FlushMode

    private[compression] val bufferSizeOrMinimum: Int = bufferSize.min(128)
  }

  object DeflateParams {

    def apply(
        bufferSize: Int = 1024 * 32,
        header: ZLibParams.Header = ZLibParams.Header.ZLIB,
        level: DeflateParams.Level = DeflateParams.Level.DEFAULT,
        strategy: DeflateParams.Strategy = DeflateParams.Strategy.DEFAULT,
        flushMode: DeflateParams.FlushMode = DeflateParams.FlushMode.DEFAULT
    ): DeflateParams =
      DeflateParamsImpl(bufferSize, header, level, strategy, flushMode)

    private case class DeflateParamsImpl(
        bufferSize: Int,
        header: ZLibParams.Header,
        level: DeflateParams.Level,
        strategy: DeflateParams.Strategy,
        flushMode: DeflateParams.FlushMode
    ) extends DeflateParams

    sealed abstract class Level(private[compression] val juzDeflaterLevel: Int)
    case object Level {
      private[fs2] def apply(level: Int): Level =
        level match {
          case DEFAULT.juzDeflaterLevel => Level.DEFAULT
          case ZERO.juzDeflaterLevel    => Level.ZERO
          case ONE.juzDeflaterLevel     => Level.ONE
          case TWO.juzDeflaterLevel     => Level.TWO
          case THREE.juzDeflaterLevel   => Level.THREE
          case FOUR.juzDeflaterLevel    => Level.FOUR
          case FIVE.juzDeflaterLevel    => Level.FIVE
          case SIX.juzDeflaterLevel     => Level.SIX
          case SEVEN.juzDeflaterLevel   => Level.SEVEN
          case EIGHT.juzDeflaterLevel   => Level.EIGHT
          case NINE.juzDeflaterLevel    => Level.NINE
        }

      case object DEFAULT extends Level(juzDeflaterLevel = Deflater.DEFAULT_COMPRESSION)
      case object BEST_SPEED extends Level(juzDeflaterLevel = Deflater.BEST_SPEED)
      case object BEST_COMPRESSION extends Level(juzDeflaterLevel = Deflater.BEST_COMPRESSION)
      case object NO_COMPRESSION extends Level(juzDeflaterLevel = Deflater.NO_COMPRESSION)
      case object ZERO extends Level(juzDeflaterLevel = 0)
      case object ONE extends Level(juzDeflaterLevel = 1)
      case object TWO extends Level(juzDeflaterLevel = 2)
      case object THREE extends Level(juzDeflaterLevel = 3)
      case object FOUR extends Level(juzDeflaterLevel = 4)
      case object FIVE extends Level(juzDeflaterLevel = 5)
      case object SIX extends Level(juzDeflaterLevel = 6)
      case object SEVEN extends Level(juzDeflaterLevel = 7)
      case object EIGHT extends Level(juzDeflaterLevel = 8)
      case object NINE extends Level(juzDeflaterLevel = 9)
    }

    sealed abstract class Strategy(private[compression] val juzDeflaterStrategy: Int)
    case object Strategy {
      private[fs2] def apply(strategy: Int): Strategy =
        strategy match {
          case DEFAULT.juzDeflaterStrategy      => Strategy.DEFAULT
          case FILTERED.juzDeflaterStrategy     => Strategy.FILTERED
          case HUFFMAN_ONLY.juzDeflaterStrategy => Strategy.HUFFMAN_ONLY
        }

      case object DEFAULT extends Strategy(juzDeflaterStrategy = Deflater.DEFAULT_STRATEGY)
      case object BEST_SPEED extends Strategy(juzDeflaterStrategy = Deflater.HUFFMAN_ONLY)
      case object BEST_COMPRESSION extends Strategy(juzDeflaterStrategy = Deflater.DEFAULT_STRATEGY)
      case object FILTERED extends Strategy(juzDeflaterStrategy = Deflater.FILTERED)
      case object HUFFMAN_ONLY extends Strategy(juzDeflaterStrategy = Deflater.HUFFMAN_ONLY)
    }

    sealed abstract class FlushMode(private[compression] val juzDeflaterFlushMode: Int)
    case object FlushMode {
      private[fs2] def apply(flushMode: Int): FlushMode =
        flushMode match {
          case DEFAULT.juzDeflaterFlushMode    => FlushMode.NO_FLUSH
          case SYNC_FLUSH.juzDeflaterFlushMode => FlushMode.SYNC_FLUSH
          case FULL_FLUSH.juzDeflaterFlushMode => FlushMode.FULL_FLUSH
        }

      case object DEFAULT extends FlushMode(juzDeflaterFlushMode = Deflater.NO_FLUSH)
      case object BEST_SPEED extends FlushMode(juzDeflaterFlushMode = Deflater.FULL_FLUSH)
      case object BEST_COMPRESSION extends FlushMode(juzDeflaterFlushMode = Deflater.NO_FLUSH)
      case object NO_FLUSH extends FlushMode(juzDeflaterFlushMode = Deflater.NO_FLUSH)
      case object SYNC_FLUSH extends FlushMode(juzDeflaterFlushMode = Deflater.SYNC_FLUSH)
      case object FULL_FLUSH extends FlushMode(juzDeflaterFlushMode = Deflater.FULL_FLUSH)
    }

    /**
      * Reasonable defaults for most applications.
      */
    val DEFAULT: DeflateParams = DeflateParams()

    /**
      * Best speed for real-time, intermittent, fragmented, interactive or discontinuous streams.
      */
    val BEST_SPEED: DeflateParams = DeflateParams(
      level = Level.BEST_SPEED,
      strategy = Strategy.BEST_SPEED,
      flushMode = FlushMode.BEST_SPEED
    )

    /**
      * Best compression for finite, complete, readily-available, continuous or file streams.
      */
    val BEST_COMPRESSION: DeflateParams = DeflateParams(
      bufferSize = 1024 * 128,
      level = Level.BEST_COMPRESSION,
      strategy = Strategy.BEST_COMPRESSION,
      flushMode = FlushMode.BEST_COMPRESSION
    )
  }

  /**
    * Returns a `Pipe` that deflates (compresses) its input elements using
    * the the Deflate algorithm.
    *
    * @param deflateParams See [[compression.DeflateParams]]
    */
  def deflate[F[_]](
      deflateParams: DeflateParams
  )(implicit SyncF: Sync[F]): Pipe[F, Byte, Byte] =
    stream =>
      Stream
        .bracket(
          SyncF.delay {
            val deflater =
              new Deflater(
                deflateParams.level.juzDeflaterLevel,
                deflateParams.header.juzDeflaterNoWrap
              )
            deflater.setStrategy(deflateParams.strategy.juzDeflaterStrategy)
            deflater
          }
        )(deflater => SyncF.delay(deflater.end()))
        .flatMap(deflater => _deflate(deflateParams, deflater, crc32 = None)(stream))

  private def _deflate[F[_]](
      deflateParams: DeflateParams,
      deflater: Deflater,
      crc32: Option[CRC32]
  ): Pipe[F, Byte, Byte] =
    _deflate_stream(deflateParams, deflater, crc32)(_).stream

  private def _deflate_chunk[F[_]](
      deflateParams: DeflateParams,
      deflater: Deflater,
      crc32: Option[CRC32],
      chunk: Chunk[Byte],
      isFinalChunk: Boolean
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
      if (isDone) 0
      else
        deflater.deflate(
          deflatedBuffer,
          0,
          deflateParams.bufferSizeOrMinimum,
          deflateParams.flushMode.juzDeflaterFlushMode
        )

    def pull(): Pull[F, Byte, Unit] = {
      val deflatedBuffer = new Array[Byte](deflateParams.bufferSizeOrMinimum)
      val deflatedBytes = deflateInto(deflatedBuffer)
      if (isDone)
        Pull.output(asChunkBytes(deflatedBuffer, deflatedBytes))
      else
        Pull.output(asChunkBytes(deflatedBuffer, deflatedBytes)) >> pull()
    }

    pull()
  }

  private def _deflate_stream[F[_]](
      deflateParams: DeflateParams,
      deflater: Deflater,
      crc32: Option[CRC32]
  ): Stream[F, Byte] => Pull[F, Byte, Unit] =
    _.pull.unconsNonEmpty.flatMap {
      case Some((inflatedChunk, inflatedStream)) =>
        _deflate_chunk(deflateParams, deflater, crc32, inflatedChunk, isFinalChunk = false) >>
          _deflate_stream(deflateParams, deflater, crc32)(inflatedStream)
      case None =>
        _deflate_chunk(deflateParams, deflater, crc32, Chunk.empty[Byte], isFinalChunk = true)
    }

  /**
    * Inflate algorithm parameters.
    */
  sealed trait InflateParams {

    /**
      * Size of the internal buffer. Default size is 32 KB.
      */
    val bufferSize: Int

    /**
      * Compression header. Defaults to [[ZLibParams.Header.ZLIB]]
      */
    val header: ZLibParams.Header

    private[compression] val bufferSizeOrMinimum: Int = bufferSize.min(128)
  }

  object InflateParams {

    def apply(
        bufferSize: Int = 1024 * 32,
        header: ZLibParams.Header = ZLibParams.Header.ZLIB
    ): InflateParams =
      InflateParamsImpl(bufferSize, header)

    /**
      * Reasonable defaults for most applications.
      */
    val DEFAULT: InflateParams = InflateParams()

    private case class InflateParamsImpl(
        bufferSize: Int,
        header: ZLibParams.Header
    ) extends InflateParams

  }

  /**
    * Returns a `Pipe` that inflates (decompresses) its input elements using
    * a `java.util.zip.Inflater` with the parameter `nowrap`.
    * @param inflateParams See [[compression.InflateParams]]
    */
  def inflate[F[_]](
      inflateParams: InflateParams
  )(implicit SyncF: Sync[F]): Pipe[F, Byte, Byte] =
    stream =>
      Stream
        .bracket(SyncF.delay(new Inflater(inflateParams.header.juzDeflaterNoWrap)))(inflater =>
          SyncF.delay(inflater.end())
        )
        .flatMap(inflater => _inflate(inflateParams, inflater, crc32 = None)(SyncF)(stream))

  private def _inflate[F[_]](
      inflateParams: InflateParams,
      inflater: Inflater,
      crc32: Option[CRC32]
  )(implicit
      SyncF: Sync[F]
  ): Pipe[F, Byte, Byte] =
    _.pull.unconsNonEmpty.flatMap {
      case Some((deflatedChunk, deflatedStream)) =>
        _inflate_chunk(inflateParams, inflater, crc32, deflatedChunk) >> _inflate_stream(
          inflateParams,
          inflater,
          crc32
        )(SyncF)(deflatedStream)
      case None =>
        Pull.done
    }.stream

  private def _inflate_chunk[F[_]](
      inflaterParams: InflateParams,
      inflater: Inflater,
      crc32: Option[CRC32],
      chunk: Chunk[Byte]
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
      val inflatedBuffer = new Array[Byte](inflaterParams.bufferSizeOrMinimum)

      inflateInto(inflatedBuffer) match {
        case inflatedBytes if inflatedBytes <= -2 =>
          inflater.getRemaining match {
            case bytesRemaining if bytesRemaining > 0 =>
              Pull.output(
                Chunk.Bytes(
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
          else Pull.output(asChunkBytes(inflatedBuffer, inflatedBytes))
        case inflatedBytes =>
          Pull.output(asChunkBytes(inflatedBuffer, inflatedBytes)) >> pull()
      }
    }

    pull()
  }

  private def _inflate_stream[F[_]](
      inflateParams: InflateParams,
      inflater: Inflater,
      crc32: Option[CRC32]
  )(implicit SyncF: Sync[F]): Stream[F, Byte] => Pull[F, Byte, Unit] =
    _.pull.unconsNonEmpty.flatMap {
      case Some((deflatedChunk, deflatedStream)) =>
        _inflate_chunk(inflateParams, inflater, crc32, deflatedChunk) >> _inflate_stream(
          inflateParams,
          inflater,
          crc32
        )(SyncF)(deflatedStream)
      case None =>
        if (!inflater.finished)
          Pull.raiseError[F](new DataFormatException("Insufficient data"))
        else
          Pull.done
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
  def gzip[F[_]](
      bufferSize: Int = 1024 * 32,
      deflateLevel: Option[Int] = None,
      deflateStrategy: Option[Int] = None,
      modificationTime: Option[Instant] = None,
      fileName: Option[String] = None,
      comment: Option[String] = None
  )(implicit SyncF: Sync[F]): Pipe[F, Byte, Byte] =
    gzip[F](
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
  def gzip[F[_]](
      fileName: Option[String],
      modificationTime: Option[Instant],
      comment: Option[String],
      deflateParams: DeflateParams
  )(implicit SyncF: Sync[F]): Pipe[F, Byte, Byte] =
    stream =>
      deflateParams match {
        case params: DeflateParams if params.header == ZLibParams.Header.GZIP =>
          Stream
            .bracket(
              SyncF.delay {
                val deflater = new Deflater(params.level.juzDeflaterLevel, true)
                deflater.setStrategy(params.strategy.juzDeflaterStrategy)
                (deflater, new CRC32())
              }
            ) { case (deflater, _) => SyncF.delay(deflater.end()) }
            .flatMap {
              case (deflater, crc32) =>
                _gzip_header(fileName, modificationTime, comment, params.level.juzDeflaterLevel) ++
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

  private def _gzip_header[F[_]](
      fileName: Option[String],
      modificationTime: Option[Instant],
      comment: Option[String],
      deflateLevel: Int
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

  /**
    * Gunzip decompression results including file properties and
    * decompressed content stream, used as follows:
    *   stream
    *     .through(gunzip[IO]())
    *     .flatMap { gunzipResult =>
    *       // Access properties here.
    *       gunzipResult.content
    *     }
    *
    * @param content Uncompressed content stream.
    * @param modificationTime Modification time of compressed file.
    * @param fileName File name.
    * @param comment File comment.
    */
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
    * @return See [[compression.GunzipResult]]
    */
  def gunzip[F[_]](
      bufferSize: Int = 1024 * 32
  )(implicit SyncF: Sync[F]): Stream[F, Byte] => Stream[F, GunzipResult[F]] =
    gunzip(
      InflateParams(
        bufferSize = bufferSize,
        header = ZLibParams.Header.GZIP
      )
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
    * @param inflateParams See [[compression.InflateParams]]
    * @return See [[compression.GunzipResult]]
    */
  def gunzip[F[_]](
      inflateParams: InflateParams
  )(implicit SyncF: Sync[F]): Stream[F, Byte] => Stream[F, GunzipResult[F]] =
    stream =>
      inflateParams match {
        case params: InflateParams if params.header == ZLibParams.Header.GZIP =>
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

  private def _gunzip_matchMandatoryHeader[F[_]](
      inflateParams: InflateParams,
      mandatoryHeaderChunk: Chunk[Byte],
      streamAfterMandatoryHeader: Stream[F, Byte],
      headerCrc32: CRC32,
      contentCrc32: CRC32,
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

  private def _gunzip_readOptionalHeader[F[_]](
      inflateParams: InflateParams,
      streamAfterMandatoryHeader: Stream[F, Byte],
      flags: Byte,
      headerCrc32: CRC32,
      contentCrc32: CRC32,
      secondsSince197001010000: Long,
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

  private def _gunzip_skipOptionalExtraField[F[_]](
      isPresent: Boolean,
      crc32: CRC32
  )(implicit Sync: Sync[F]): Pipe[F, Byte, Byte] =
    stream =>
      if (isPresent)
        stream.pull
          .unconsN(gzipOptionalExtraFieldLengthBytes)
          .flatMap {
            case Some((optionalExtraFieldLengthChunk, streamAfterOptionalExtraFieldLength)) =>
              (
                optionalExtraFieldLengthChunk.size,
                optionalExtraFieldLengthChunk.toBytes.values
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
      else stream

  private def _gunzip_readOptionalStringField[F[_]](
      isPresent: Boolean,
      crc32: CRC32,
      fieldName: String,
      fieldBytesSoftLimit: Int
  )(implicit
      SyncF: Sync[F]
  ): Stream[F, Byte] => Stream[F, (Option[String], Stream[F, Byte])] =
    stream =>
      if (isPresent)
        unconsUntil[F, Byte](_ == zeroByte, fieldBytesSoftLimit)(stream).flatMap {
          case Some((chunk, rest)) =>
            Pull.output1(
              (
                if (chunk.isEmpty)
                  Some("")
                else {
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
        }.stream
      else Stream.emit((Option.empty[String], stream))

  private def _gunzip_validateHeader[F[_]](
      isPresent: Boolean,
      crc32: CRC32
  )(implicit SyncF: Sync[F]): Pipe[F, Byte, Byte] =
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
            val actualInputSize = inflater.getBytesWritten & 0xffffffffL
            if (expectedInputCrc32 != actualInputCrc32)
              Pull.raiseError(new ZipException("Content failed CRC validation"))
            else if (expectedInputSize != actualInputSize)
              Pull.raiseError(new ZipException("Content failed size validation"))
            else
              Pull.done
          } else Pull.raiseError(new ZipException("Failed to read trailer (1)"))

        def streamUntilTrailer(last: Chunk[Byte]): Stream[F, Byte] => Pull[F, Byte, Unit] =
          _.pull.unconsNonEmpty
            .flatMap {
              case Some((next, rest)) =>
                if (inflater.finished())
                  if (next.size >= gzipTrailerBytes)
                    if (last.nonEmpty) Pull.output(last) >> streamUntilTrailer(next)(rest)
                    else streamUntilTrailer(next)(rest)
                  else
                    streamUntilTrailer(Chunk.concatBytes(List(last.toBytes, next.toBytes)))(rest)
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

  /**
    * Like Stream.unconsN, but returns a chunk of elements that do not satisfy the predicate, splitting chunk as necessary.
    * Elements will not be dropped after the soft limit is breached.
    *
    * `Pull.pure(None)` is returned if the end of the source stream is reached.
    */
  private def unconsUntil[F[_], O](
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

  private def asChunkBytes(values: Array[Byte]): Chunk[Byte] =
    asChunkBytes(values, values.length)

  private def asChunkBytes(values: Array[Byte], length: Int): Chunk[Byte] =
    if (length > 0) Chunk.Bytes(values, 0, length) else Chunk.empty[Byte]

  private def unsignedToInt(lsb: Byte, msb: Byte): Int =
    ((msb & 0xff) << 8) | (lsb & 0xff)

  private def unsignedToLong(lsb: Byte, byte2: Byte, byte3: Byte, msb: Byte): Long =
    ((msb.toLong & 0xff) << 24) | ((byte3 & 0xff) << 16) | ((byte2 & 0xff) << 8) | (lsb & 0xff)
}
