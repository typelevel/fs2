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

/** Provides the capability to compress/decompress using deflate and gzip. On JVM an instance is
  * available given a `Sync[F]`. On Node.js an instance is available for `Async[F]` by importing
  * `fs2.io.compression._`.
  */
sealed trait Compression[F[_]] extends CompressionPlatform[F] {

  def deflate(deflateParams: DeflateParams): Pipe[F, Byte, Byte]

  def inflate(inflateParams: InflateParams): Pipe[F, Byte, Byte]

}

object Compression extends CompressionCompanionPlatform {
  private[fs2] trait UnsealedCompression[F[_]] extends Compression[F]

  def apply[F[_]](implicit F: Compression[F]): Compression[F] = F
}
