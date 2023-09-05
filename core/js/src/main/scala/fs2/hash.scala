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

import org.typelevel.scalaccompat.annotation._

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array

/** Provides various cryptographic hashes as pipes. Supported only on Node.js. */
object hash {

  /** Computes an MD2 digest. */
  def md2[F[_]]: Pipe[F, Byte, Byte] = digest(createHash("md2"))

  /** Computes an MD5 digest. */
  def md5[F[_]]: Pipe[F, Byte, Byte] = digest(createHash("md5"))

  /** Computes a SHA-1 digest. */
  def sha1[F[_]]: Pipe[F, Byte, Byte] =
    digest(createHash("sha1"))

  /** Computes a SHA-256 digest. */
  def sha256[F[_]]: Pipe[F, Byte, Byte] =
    digest(createHash("sha256"))

  /** Computes a SHA-384 digest. */
  def sha384[F[_]]: Pipe[F, Byte, Byte] =
    digest(createHash("sha384"))

  /** Computes a SHA-512 digest. */
  def sha512[F[_]]: Pipe[F, Byte, Byte] =
    digest(createHash("sha512"))

  /** Computes the digest of the source stream, emitting the digest as a chunk
    * after completion of the source stream.
    */
  private[this] def digest[F[_]](hash: => Hash): Pipe[F, Byte, Byte] =
    in =>
      Stream.suspend {
        in.chunks
          .fold(hash) { (d, c) =>
            val bytes = c.toUint8Array
            d.update(bytes)
            d
          }
          .flatMap(d => Stream.chunk(Chunk.uint8Array(d.digest())))
      }

  @js.native
  @JSImport("crypto", "createHash")
  @nowarn212("cat=unused")
  private[fs2] def createHash(algorithm: String): Hash = js.native

  @js.native
  @nowarn212("cat=unused")
  private[fs2] trait Hash extends js.Object {
    def update(data: Uint8Array): Unit = js.native
    def digest(): Uint8Array = js.native
  }
}
