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

import cats.effect.kernel.Sync
import fs2.hashing.{Hashing, HashAlgorithm}

/** Provides various cryptographic hashes as pipes. Requires OpenSSL. */
@deprecated("Use fs2.hashing.Hashing[F] instead", "3.11.0")
object hash {

  /** Computes an MD2 digest. */
  def md2[F[_]: Sync]: Pipe[F, Byte, Byte] = digest(HashAlgorithm.Named("MD2"))

  /** Computes an MD5 digest. */
  def md5[F[_]: Sync]: Pipe[F, Byte, Byte] = digest(HashAlgorithm.MD5)

  /** Computes a SHA-1 digest. */
  def sha1[F[_]: Sync]: Pipe[F, Byte, Byte] = digest(HashAlgorithm.SHA1)

  /** Computes a SHA-256 digest. */
  def sha256[F[_]: Sync]: Pipe[F, Byte, Byte] = digest(HashAlgorithm.SHA256)

  /** Computes a SHA-384 digest. */
  def sha384[F[_]: Sync]: Pipe[F, Byte, Byte] = digest(HashAlgorithm.SHA384)

  /** Computes a SHA-512 digest. */
  def sha512[F[_]: Sync]: Pipe[F, Byte, Byte] = digest(HashAlgorithm.SHA512)

  private[this] def digest[F[_]](
      algorithm: HashAlgorithm
  )(implicit F: Sync[F]): Pipe[F, Byte, Byte] = {
    val h = Hashing.forSync[F]
    s => h.hashWith(h.create(algorithm))(s).map(_.bytes).unchunks
  }
}
