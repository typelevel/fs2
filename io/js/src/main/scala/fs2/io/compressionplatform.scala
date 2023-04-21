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
package io

import cats.effect.IO
import cats.effect.LiftIO
import cats.effect.kernel.Async
import cats.syntax.all._
import fs2.compression.Compression
import fs2.compression.DeflateParams
import fs2.compression.GunzipResult
import fs2.compression.InflateParams
import fs2.compression.ZLibParams
import fs2.io.internal.facade

private[io] trait compressionplatform {

  class ZipException(msg: String) extends IOException(msg)

  def fs2ioCompressionForIO: Compression[IO] =
    fs2ioCompressionForLiftIO

  implicit def fs2ioCompressionForLiftIO[F[_]: Async: LiftIO]: Compression[F] = {
    val _ = LiftIO[F]
    fs2ioCompressionForAsync
  }

  def fs2ioCompressionForAsync[F[_]](implicit F: Async[F]): Compression[F] =
    new Compression.UnsealedCompression[F] {

      def deflate(deflateParams: DeflateParams): Pipe[F, Byte, Byte] =
        deflateImpl(deflateParams) { options =>
          deflateParams.header match {
            case ZLibParams.Header.GZIP => facade.zlib.createDeflateRaw(options)
            case ZLibParams.Header.ZLIB => facade.zlib.createDeflate(options)
          }
        }

      def inflate(inflateParams: InflateParams): Pipe[F, Byte, Byte] =
        inflateImpl(inflateParams) { options =>
          inflateParams.header match {
            case ZLibParams.Header.GZIP => facade.zlib.createInflateRaw(options)
            case ZLibParams.Header.ZLIB => facade.zlib.createInflate(options)
          }
        }

      def gzip(
          fileName: Option[Nothing],
          modificationTime: Option[Nothing],
          comment: Option[Nothing],
          deflateParams: DeflateParams
      ): Pipe[F, Byte, Byte] = deflateImpl(deflateParams) { options =>
        require(!deflateParams.fhCrcEnabled, "FHCRC is not supported on Node.js")
        deflateParams.header match {
          case ZLibParams.Header.GZIP => facade.zlib.createGzip(options)
          case ZLibParams.Header.ZLIB =>
            throw new ZipException(
              s"${ZLibParams.Header.GZIP} header type required, not ${deflateParams.header}."
            )
        }
      }

      def gunzip(inflateParams: InflateParams): Stream[F, Byte] => Stream[F, GunzipResult[F]] =
        in => {
          val content = inflateImpl(inflateParams) { options =>
            inflateParams.header match {
              case ZLibParams.Header.GZIP => facade.zlib.createGunzip(options)
              case ZLibParams.Header.ZLIB =>
                throw new ZipException(
                  s"${ZLibParams.Header.GZIP} header type required, not ${inflateParams.header}."
                )
            }
          }
          Stream.emit(GunzipResult(content(in)))
        }

      def deflateImpl(
          deflateParams: DeflateParams
      )(f: facade.zlib.Options => facade.zlib.Zlib): Pipe[F, Byte, Byte] = in => {
        val options = new facade.zlib.Options {
          chunkSize = deflateParams.bufferSizeOrMinimum
          level = deflateParams.level.juzDeflaterLevel
          strategy = deflateParams.strategy.juzDeflaterStrategy
          flush = deflateParams.flushMode.juzDeflaterFlushMode
        }

        Stream
          .resource(suspendReadableAndRead()(f(options)))
          .flatMap { case (deflate, out) =>
            out
              .concurrently(in.through(writeWritable[F](deflate.pure.widen)))
              .onFinalize(
                F.async_[Unit](cb => deflate.close(() => cb(Right(()))))
              )
          }
      }

      def inflateImpl(
          inflateParams: InflateParams
      )(f: facade.zlib.Options => facade.zlib.Zlib): Pipe[F, Byte, Byte] = in => {
        val options = new facade.zlib.Options {
          chunkSize = inflateParams.bufferSizeOrMinimum
        }

        Stream
          .resource(suspendReadableAndRead()(f(options)))
          .flatMap { case (inflate, out) =>
            out
              .concurrently(in.through(writeWritable[F](inflate.pure.widen)))
              .onFinalize(
                F.async_[Unit](cb => inflate.close(() => cb(Right(()))))
              )
          }
      }

    }
}
