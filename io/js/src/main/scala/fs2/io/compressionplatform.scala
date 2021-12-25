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

import cats.effect.Concurrent
import cats.effect.kernel.Async
import cats.syntax.all._
import fs2.compression._
import fs2.internal.jsdeps.node.zlibMod

import scala.concurrent.duration.FiniteDuration

private[fs2] trait compressionplatform {

  implicit def fs2ioCompressionForAsync[F[_]: Concurrent](implicit F: Async[F]): Compression[F] =
    new Compression.UnsealedCompression[F] {

      private val gzip = new Gzip[F]

      override def deflate(deflateParams: DeflateParams): Pipe[F, Byte, Byte] = in => {
        val options = zlibMod
          .ZlibOptions()
          .setChunkSize(deflateParams.bufferSizeOrMinimum.toDouble)
          .setLevel(deflateParams.level.juzDeflaterLevel.toDouble)
          .setStrategy(deflateParams.strategy.juzDeflaterStrategy.toDouble)
          .setFlush(deflateParams.flushMode.juzDeflaterFlushMode.toDouble)

        Stream
          .resource(suspendReadableAndRead() {
            (deflateParams.header match {
              case ZLibParams.Header.GZIP => zlibMod.createGzip(options)
              case ZLibParams.Header.ZLIB => zlibMod.createDeflate(options)
            }).asInstanceOf[Duplex]
          })
          .flatMap { case (deflate, out) =>
            out
              .concurrently(in.through(writeWritable[F](deflate.pure.widen)))
              .onFinalize(
                F.async_[Unit](cb => deflate.asInstanceOf[zlibMod.Zlib].close(() => cb(Right(()))))
              )
          }
      }

      override def inflate(inflateParams: InflateParams): Pipe[F, Byte, Byte] = in => {
        val options = zlibMod
          .ZlibOptions()
          .setChunkSize(inflateParams.bufferSizeOrMinimum.toDouble)
        Stream
          .resource(suspendReadableAndRead() {
            (inflateParams.header match {
              case ZLibParams.Header.GZIP => zlibMod.createGunzip(options)
              case ZLibParams.Header.ZLIB => zlibMod.createInflate(options)
            }).asInstanceOf[Duplex]
          })
          .flatMap { case (inflate, out) =>
            out
              .concurrently(in.through(writeWritable[F](inflate.pure.widen)))
              .onFinalize(
                F.async_[Unit](cb => inflate.asInstanceOf[zlibMod.Zlib].close(() => cb(Right(()))))
              )
          }
      }

      def gzip2(
          fileName: Option[String],
          modificationTime: Option[FiniteDuration],
          comment: Option[String],
          deflateParams: DeflateParams
      ): Pipe[F, Byte, Byte] =
        gzip.gzip(fileName, modificationTime, comment, deflate(deflateParams), deflateParams)

      def gunzip(inflateParams: InflateParams): Stream[F, Byte] => Stream[F, GunzipResult[F]] =
        gzip.gunzip(inflate(inflateParams), inflateParams)

    }
}
