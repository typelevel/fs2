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

import cats.effect.kernel.Async
import cats.syntax.all._
import fs2.compression.Compression
import fs2.compression.DeflateParams
import fs2.compression.InflateParams
import fs2.compression.ZLibParams
import fs2.internal.jsdeps.node.zlibMod

private[io] trait compressionplatform {

  implicit def fs2ioCompressionForAsync[F[_]](implicit F: Async[F]): Compression[F] =
    new Compression.UnsealedCompression[F] {

      override def deflate(deflateParams: DeflateParams): Pipe[F, Byte, Byte] = in => {
        val options = zlibMod
          .ZlibOptions()
          .setChunkSize(deflateParams.bufferSizeOrMinimum.toDouble)
          .setLevel(deflateParams.level.juzDeflaterLevel.toDouble)
          .setStrategy(deflateParams.strategy.juzDeflaterStrategy.toDouble)
          .setFlush(deflateParams.flushMode.juzDeflaterFlushMode.toDouble)
        Stream
          .bracket(F.delay(deflateParams.header match {
            case ZLibParams.Header.GZIP => zlibMod.createGzip(options)
            case ZLibParams.Header.ZLIB => zlibMod.createDeflate(options)
          }))(z => F.async_(cb => z.close(() => cb(Right(())))))
          .flatMap { _deflate =>
            val deflate = _deflate.asInstanceOf[Duplex].pure
            Stream
              .resource(readReadableResource[F](deflate.widen))
              .flatMap(_.concurrently(in.through(writeWritable[F](deflate.widen))))
          }
      }

      override def inflate(inflateParams: InflateParams): Pipe[F, Byte, Byte] = in => {
        val options = zlibMod
          .ZlibOptions()
          .setChunkSize(inflateParams.bufferSizeOrMinimum.toDouble)
        Stream
          .bracket(F.delay(inflateParams.header match {
            case ZLibParams.Header.GZIP => zlibMod.createGunzip(options)
            case ZLibParams.Header.ZLIB => zlibMod.createInflate(options)
          }))(z => F.async_(cb => z.close(() => cb(Right(())))))
          .flatMap { _inflate =>
            val inflate = _inflate.asInstanceOf[Duplex].pure
            Stream
              .resource(readReadableResource[F](inflate.widen))
              .flatMap(_.concurrently(in.through(writeWritable[F](inflate.widen))))
          }
      }

    }

}
