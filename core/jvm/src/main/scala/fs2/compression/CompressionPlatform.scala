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

import cats.effect.Ref
import cats.effect.kernel.Sync
import cats.syntax.all._
import fs2.compression.internal.CrcBuilder

import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.zip.{Deflater, Inflater}
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import scala.util.chaining._

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
      bufferSize: Int,
      deflateLevel: Option[Int],
      deflateStrategy: Option[Int],
      modificationTime: Option[Instant],
      fileName: Option[String],
      comment: Option[String]
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
  ): Pipe[F, Byte, Byte] = gzip(
    fileName,
    modificationTime.map(i => FiniteDuration(i.getEpochSecond, TimeUnit.SECONDS)),
    comment,
    deflateParams
  )

}

private[compression] trait CompressionCompanionPlatform {

  implicit def forSync[F[_]](implicit F: Sync[F]): Compression[F] =
    new Compression.UnsealedCompression[F] {

      private val gzip = new Gzip[F]

      def deflate(deflateParams: DeflateParams): Pipe[F, Byte, Byte] =
        stream =>
          Stream
            .bracket(
              F.delay {
                new Deflater(
                  deflateParams.level.juzDeflaterLevel,
                  deflateParams.header.juzDeflaterNoWrap
                ).tap(_.setStrategy(deflateParams.strategy.juzDeflaterStrategy))
              }
            )(deflater => F.delay(deflater.end()))
            .flatMap(deflater => _deflate(deflateParams, deflater, crc32 = None)(stream))

      private def _deflate(
          deflateParams: DeflateParams,
          deflater: Deflater,
          crc32: Option[CrcBuilder]
      ): Pipe[F, Byte, Byte] =
        in =>
          Stream.suspend {
            val deflatedBuffer = new Array[Byte](deflateParams.bufferSizeOrMinimum)
            _deflate_stream(deflateParams, deflater, crc32, deflatedBuffer)(in).stream
          }

      private def _deflate_chunk(
          deflateParams: DeflateParams,
          deflater: Deflater,
          crc32: Option[CrcBuilder],
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
          crc32: Option[CrcBuilder],
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
        stream => inflateAndTrailer(inflateParams, 0)(stream).flatMap(_._1)

      private def inflateAndTrailer(
          inflateParams: InflateParams,
          trailerSize: Int
      ): Stream[F, Byte] => Stream[
        F,
        (Stream[F, Byte], Ref[F, Chunk[Byte]], Ref[F, Long], Ref[F, Long])
      ] = in =>
        Stream.suspend {
          Stream
            .eval(
              (
                Ref.of[F, Chunk[Byte]](Chunk.empty),
                Ref.of[F, Long](0),
                Ref.of[F, Long](0)
              ).tupled
            )
            .map { case (trailerChunk, bytesWritten, crc32) =>
              (
                _inflate_chunks(inflateParams, trailerChunk, bytesWritten, crc32, trailerSize)(in),
                trailerChunk,
                bytesWritten,
                crc32
              )
            }
        }

      private def _inflate_chunks(
          inflateParams: InflateParams,
          trailerChunk: Ref[F, Chunk[Byte]],
          bytesWritten: Ref[F, Long],
          crc32: Ref[F, Long],
          trailerSize: Int
      ): Stream[F, Byte] => Stream[F, Byte] = stream =>
        Stream
          .eval(F.delay(new Inflater(inflateParams.header.juzDeflaterNoWrap)))
          .flatMap { inflater =>
            val inflatedBuffer = new Array[Byte](inflateParams.bufferSizeOrMinimum)
            val crcBuilder = new CrcBuilder

            def setTrailerChunk(
                remaining: Chunk[Byte],
                s: Stream[F, Byte]
            ): Pull[F, INothing, Unit] =
              Pull.eval {
                s
                  .take((trailerSize - remaining.size).toLong)
                  .chunkAll
                  .compile
                  .last
                  .flatMap {
                    case None          => F.unit
                    case Some(trailer) => trailerChunk.set(remaining ++ trailer)
                  } >>
                  bytesWritten.set(inflater.getBytesWritten) >>
                  crc32.set(crcBuilder.getValue)
              }

            def inflateChunk(
                bytesChunk: Chunk.ArraySlice[Byte],
                offset: Int
            ): Pull[F, Byte, Option[Chunk[Byte]]] = {
              inflater.setInput(
                bytesChunk.values,
                bytesChunk.offset + offset,
                bytesChunk.length - offset
              )
              val inflatedBytes = inflater.inflate(inflatedBuffer)
              crcBuilder.update(inflatedBuffer, 0, inflatedBytes)
              Pull.output(copyAsChunkBytes(inflatedBuffer, inflatedBytes)) >> {
                val remainingBytes = inflater.getRemaining
                if (!inflater.finished()) {
                  if (remainingBytes > 0)
                    inflateChunk(bytesChunk, bytesChunk.length - remainingBytes)
                  else
                    Pull.pure(none)
                } else {
                  if (remainingBytes > 0)
                    Pull.pure(
                      Chunk
                        .array(
                          bytesChunk.values,
                          bytesChunk.offset + bytesChunk.length - remainingBytes,
                          if (remainingBytes < trailerSize) remainingBytes
                          else trailerSize // don't need more than that
                        )
                        .some
                    )
                  else
                    Pull.pure(Chunk.empty.some)
                }
              }
            }

            def pull: Stream[F, Byte] => Pull[F, Byte, Unit] = in =>
              in.pull.uncons.flatMap {
                case None => Pull.done
                case Some((chunk, rest)) =>
                  inflateChunk(chunk.toArraySlice, 0).flatMap {
                    case None            => pull(rest)
                    case Some(remaining) => setTrailerChunk(remaining, rest)
                  }
              }

            pull(stream)
              .flatMap { _ =>
                Pull.eval(F.delay(inflater.end()))
              }
              .handleErrorWith { case NonFatal(e) =>
                Pull.eval(F.delay(inflater.end())) >> Pull.raiseError(e)
              }
              .stream
//              .onFinalize {
//                F.delay(inflater.end())
//              }
          }

      def gzip(
          fileName: Option[String],
          modificationTime: Option[FiniteDuration],
          comment: Option[String],
          deflateParams: DeflateParams
      )(implicit d: DummyImplicit): Pipe[F, Byte, Byte] =
        gzip.gzip(
          fileName,
          modificationTime,
          comment,
          deflate(deflateParams),
          deflateParams,
          System.getProperty("os.name")
        )

      def gunzip(inflateParams: InflateParams): Stream[F, Byte] => Stream[F, GunzipResult[F]] =
        gzip.gunzip(inflateAndTrailer(inflateParams, gzip.gzipTrailerBytes), inflateParams)

      private def copyAsChunkBytes(values: Array[Byte], length: Int): Chunk[Byte] =
        if (length > 0) {
          val target = new Array[Byte](length)
          System.arraycopy(values, 0, target, 0, length)
          Chunk.array(target, 0, length)
        } else Chunk.empty[Byte]

    }
}
