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

import scala.concurrent.duration._

/** Provides the capability to compress/decompress using deflate and gzip.
  * On JVM an instance is available given a `Sync[F]`.
  * On Node.js an instance is available for `Async[F]` by importing `fs2.io.compression._`.
  */
trait Compression[F[_]] extends CompressionPlatform[F] {

  def deflate(deflateParams: DeflateParams): Pipe[F, Byte, Byte]

  def inflate(inflateParams: InflateParams): Pipe[F, Byte, Byte]

  /** Identical to [[gzip]] but will compile on both JVM and JS Platforms.
    */
  def gzipCrossPlatform(
      bufferSize: Int = 1024 * 32,
      deflateLevel: Option[Int] = None,
      deflateStrategy: Option[Int] = None,
      modificationTime: Option[FiniteDuration] = None,
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
      modificationTime: Option[FiniteDuration],
      comment: Option[String],
      deflateParams: DeflateParams
  )(implicit dummy: DummyImplicit): Pipe[F, Byte, Byte]

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

object Compression extends CompressionCompanionPlatform {
  def apply[F[_]](implicit F: Compression[F]): Compression[F] = F
}
