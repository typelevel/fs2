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
import org.typelevel.scalaccompat.annotation._

import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

/** Provides various cryptographic hashes as pipes. Requires OpenSSL. */
object hash {
  import openssl._

  /** Computes an MD2 digest. */
  def md2[F[_]: Sync]: Pipe[F, Byte, Byte] = digest(c"MD2")

  /** Computes an MD5 digest. */
  def md5[F[_]: Sync]: Pipe[F, Byte, Byte] = digest(c"MD5")

  /** Computes a SHA-1 digest. */
  def sha1[F[_]: Sync]: Pipe[F, Byte, Byte] = digest(c"SHA1")

  /** Computes a SHA-256 digest. */
  def sha256[F[_]: Sync]: Pipe[F, Byte, Byte] = digest(c"SHA256")

  /** Computes a SHA-384 digest. */
  def sha384[F[_]: Sync]: Pipe[F, Byte, Byte] = digest(c"SHA384")

  /** Computes a SHA-512 digest. */
  def sha512[F[_]: Sync]: Pipe[F, Byte, Byte] = digest(c"SHA512")

  /** Computes the digest of the source stream, emitting the digest as a chunk
    * after completion of the source stream.
    */
  private[this] def digest[F[_]](digest: CString)(implicit F: Sync[F]): Pipe[F, Byte, Byte] =
    in =>
      Stream
        .bracket(F.delay {
          val ctx = EVP_MD_CTX_new()
          if (ctx == null)
            throw new RuntimeException(s"EVP_MD_CTX_new: ${getError()}")
          ctx
        })(ctx => F.delay(EVP_MD_CTX_free(ctx)))
        .evalTap { ctx =>
          F.delay {
            val `type` = EVP_get_digestbyname(digest)
            if (`type` == null)
              throw new RuntimeException(s"EVP_get_digestbyname: ${getError()}")
            if (EVP_DigestInit_ex(ctx, `type`, null) != 1)
              throw new RuntimeException(s"EVP_DigestInit_ex: ${getError()}")
          }
        }
        .flatMap { ctx =>
          in.chunks
            .foreach { chunk =>
              F.delay {
                val Chunk.ArraySlice(values, offset, size) = chunk.toArraySlice
                if (EVP_DigestUpdate(ctx, values.atUnsafe(offset), size.toULong) != 1)
                  throw new RuntimeException(s"EVP_DigestUpdate: ${getError()}")
              }
            } ++ Stream
            .evalUnChunk {
              F.delay[Chunk[Byte]] {
                val md = new Array[Byte](EVP_MAX_MD_SIZE)
                val size = stackalloc[CUnsignedInt]()
                if (EVP_DigestFinal_ex(ctx, md.atUnsafe(0), size) != 1)
                  throw new RuntimeException(s"EVP_DigestFinal_ex: ${getError()}")
                Chunk.ArraySlice(md, 0, (!size).toInt)
              }
            }
        }

  private[this] def getError(): String =
    fromCString(ERR_reason_error_string(ERR_get_error()))

  @link("crypto")
  @extern
  @nowarn212("cat=unused")
  private[fs2] object openssl {

    final val EVP_MAX_MD_SIZE = 64

    type EVP_MD
    type EVP_MD_CTX
    type ENGINE

    def ERR_get_error(): ULong = extern
    def ERR_reason_error_string(e: ULong): CString = extern

    def EVP_get_digestbyname(name: Ptr[CChar]): Ptr[EVP_MD] = extern

    def EVP_MD_CTX_new(): Ptr[EVP_MD_CTX] = extern
    def EVP_MD_CTX_free(ctx: Ptr[EVP_MD_CTX]): Unit = extern

    def EVP_DigestInit_ex(ctx: Ptr[EVP_MD_CTX], `type`: Ptr[EVP_MD], impl: Ptr[ENGINE]): CInt =
      extern
    def EVP_DigestUpdate(ctx: Ptr[EVP_MD_CTX], d: Ptr[Byte], cnt: CSize): CInt = extern
    def EVP_DigestFinal_ex(ctx: Ptr[EVP_MD_CTX], md: Ptr[Byte], s: Ptr[CUnsignedInt]): CInt = extern
    def EVP_Digest(
        data: Ptr[Byte],
        count: CSize,
        md: Ptr[Byte],
        size: Ptr[CUnsignedInt],
        `type`: Ptr[EVP_MD],
        impl: Ptr[ENGINE]
    ): CInt = extern
  }
}
