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

import java.security.MessageDigest

/** Provides various cryptographic hashes as pipes. */
object hash {

  /** Computes an MD2 digest. */
  def md2[F[_]]: Pipe[F, Byte, Byte] = digest(MessageDigest.getInstance("MD2"))

  /** Computes an MD5 digest. */
  def md5[F[_]]: Pipe[F, Byte, Byte] = digest(MessageDigest.getInstance("MD5"))

  /** Computes a SHA-1 digest. */
  def sha1[F[_]]: Pipe[F, Byte, Byte] =
    digest(MessageDigest.getInstance("SHA-1"))

  /** Computes a SHA-256 digest. */
  def sha256[F[_]]: Pipe[F, Byte, Byte] =
    digest(MessageDigest.getInstance("SHA-256"))

  /** Computes a SHA-384 digest. */
  def sha384[F[_]]: Pipe[F, Byte, Byte] =
    digest(MessageDigest.getInstance("SHA-384"))

  /** Computes a SHA-512 digest. */
  def sha512[F[_]]: Pipe[F, Byte, Byte] =
    digest(MessageDigest.getInstance("SHA-512"))

  /** Computes the digest of the source stream, emitting the digest as a chunk after completion of
    * the source stream.
    */
  def digest[F[_]](digest: => MessageDigest): Pipe[F, Byte, Byte] =
    in =>
      Stream.suspend {
        in.chunks
          .fold(digest) { (d, c) =>
            val bytes = c.toArraySlice
            d.update(bytes.values, bytes.offset, bytes.size)
            d
          }
          .flatMap(d => Stream.chunk(Chunk.array(d.digest())))
      }
}
